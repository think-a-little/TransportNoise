package org.example.transport_noise.service;

import org.example.transport_noise.model.FileAnalysisResult;
import org.example.transport_noise.model.FileHeader;

import java.sql.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

public class DatabaseService {
    private static DatabaseService instance;

    private static final String URL = "jdbc:postgresql://localhost:5432/noisedb";
    private static final String USER = "postgres";
    private static final String PASSWORD = "32676";

    private static final boolean AUTO_CREATE_TABLES = true;

    private static final String INSERT_SIGNAL_DATA =
            "INSERT INTO signal_data (file_name, record_start_time, latitude, longitude, " +
                    "record_time, value, trace_number) VALUES (?, ?, ?, ?, ?, ?, ?)";

    private static final String INSERT_STATISTICS =
            "INSERT INTO signal_statistics (file_name, record_start_time, second_number, " +
                    "variance, sample_count, latitude, longitude) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?) " +
                    "ON CONFLICT (file_name, second_number) DO UPDATE SET variance = EXCLUDED.variance";

    private DatabaseService() {
        try {
            Class.forName("org.postgresql.Driver");
            System.out.println("✅ PostgreSQL Driver загружен");
            initializeDatabase();
        } catch (ClassNotFoundException e) {
            System.err.println("❌ Ошибка драйвера: " + e.getMessage());
        } catch (SQLException e) {
            System.err.println("❌ Ошибка БД: " + e.getMessage());
        }
    }

    private void initializeDatabase() throws SQLException {
        try (Connection conn = getConnection()) {
            System.out.println("✅ Подключено к PostgreSQL: noisedb");

            try (Statement stmt = conn.createStatement()) {
                stmt.execute("SET search_path TO public");
            }

            if (AUTO_CREATE_TABLES) {
                createTablesIfNotExist(conn);
            }
        }
    }

    private void createTablesIfNotExist(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement()) {

            // signal_data
            String createSignalData =
                    "CREATE TABLE IF NOT EXISTS signal_data (" +
                            "    id BIGSERIAL PRIMARY KEY," +
                            "    file_name TEXT NOT NULL," +
                            "    record_start_time TIMESTAMP WITH TIME ZONE NOT NULL," +
                            "    latitude DOUBLE PRECISION NOT NULL," +
                            "    longitude DOUBLE PRECISION NOT NULL," +
                            "    record_time TIMESTAMP WITH TIME ZONE NOT NULL," +
                            "    value DOUBLE PRECISION NOT NULL," +
                            "    trace_number SMALLINT DEFAULT 1," +
                            "    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()" +
                            ")";
            stmt.execute(createSignalData);
            System.out.println("   ✅ signal_data");

            // signal_statistics
            String createSignalStatistics =
                    "CREATE TABLE IF NOT EXISTS signal_statistics (" +
                            "    id BIGSERIAL PRIMARY KEY," +
                            "    file_name TEXT NOT NULL," +
                            "    record_start_time TIMESTAMP WITH TIME ZONE NOT NULL," +
                            "    second_number INTEGER NOT NULL," +
                            "    variance DOUBLE PRECISION NOT NULL," +
                            "    sample_count INTEGER NOT NULL," +
                            "    latitude DOUBLE PRECISION NOT NULL," +
                            "    longitude DOUBLE PRECISION NOT NULL," +
                            "    UNIQUE(file_name, second_number)" +
                            ")";
            stmt.execute(createSignalStatistics);
            System.out.println("   ✅ signal_statistics");

            // Индексы
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_signal_data_time ON signal_data(record_time)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_signal_data_trace ON signal_data(trace_number)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_signal_data_trace_time ON signal_data(trace_number, record_time)");
        }
    }

    public static synchronized DatabaseService getInstance() {
        if (instance == null) {
            instance = new DatabaseService();
        }
        return instance;
    }

    private Connection getConnection() throws SQLException {
        return DriverManager.getConnection(URL, USER, PASSWORD);
    }

    /**
     * Сохранение данных в БД
     */
    public void saveAnalysisResult(FileAnalysisResult result) throws SQLException {
        String fileName = result.getFileName();

        System.out.println("💾 Сохранение: " + fileName);

        try (Connection conn = getConnection()) {
            conn.setAutoCommit(false);

            try {
                int savedSamples = saveRawData(conn, result);
                int savedStats = saveStatistics(conn, result);

                conn.commit();
                System.out.println("   ✅ Отсчётов: " + savedSamples + ", статистика: " + savedStats);

            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        }
    }

    private int saveRawData(Connection conn, FileAnalysisResult result) throws SQLException {
        FileHeader header = result.getHeader();
        Timestamp recordStartTime = convertToTimestamp(header);
        double lat = header.getLat();
        double lon = header.getLon();
        int sampleRate = result.getSampleRate();
        List<Double> rawSamples = result.getRawSamples();
        int totalInserted = 0;

        try (PreparedStatement pstmt = conn.prepareStatement(INSERT_SIGNAL_DATA)) {
            int batchSize = 0;

            for (int i = 0; i < rawSamples.size(); i++) {
                double secondsOffset = (double) i / sampleRate;
                Timestamp recordTime = new Timestamp(
                        recordStartTime.getTime() + (long)(secondsOffset * 1000)
                );

                pstmt.setString(1, result.getFileName());
                pstmt.setTimestamp(2, recordStartTime);
                pstmt.setDouble(3, lat);
                pstmt.setDouble(4, lon);
                pstmt.setTimestamp(5, recordTime);
                pstmt.setDouble(6, rawSamples.get(i));
                pstmt.setShort(7, header.getTrNum());

                pstmt.addBatch();
                batchSize++;

                if (batchSize >= 1000) {
                    int[] results = pstmt.executeBatch();
                    for (int r : results) {
                        if (r > 0) totalInserted += r;
                    }
                    batchSize = 0;
                }
            }

            if (batchSize > 0) {
                int[] results = pstmt.executeBatch();
                for (int r : results) {
                    if (r > 0) totalInserted += r;
                }
            }
        }

        return totalInserted;
    }

    private int saveStatistics(Connection conn, FileAnalysisResult result) throws SQLException {
        FileHeader header = result.getHeader();
        Timestamp recordStartTime = convertToTimestamp(header);
        List<Double> variances = result.getVariances();
        int sampleRate = result.getSampleRate();
        int totalInserted = 0;

        try (PreparedStatement pstmt = conn.prepareStatement(INSERT_STATISTICS)) {
            for (int i = 0; i < variances.size(); i++) {
                pstmt.setString(1, result.getFileName());
                pstmt.setTimestamp(2, recordStartTime);
                pstmt.setInt(3, i + 1);
                pstmt.setDouble(4, variances.get(i));

                int samplesInSecond = sampleRate;
                if (i == variances.size() - 1) {
                    samplesInSecond = result.getTotalSamples() - (i * sampleRate);
                }
                pstmt.setInt(5, samplesInSecond);
                pstmt.setDouble(6, header.getLat());
                pstmt.setDouble(7, header.getLon());

                pstmt.addBatch();
            }

            int[] results = pstmt.executeBatch();
            for (int r : results) {
                if (r > 0) totalInserted += r;
            }
        }

        return totalInserted;
    }

    private Timestamp convertToTimestamp(FileHeader header) {
        int year = header.getYear() & 0xFF; // Получаем год (0-255)

        // Исправление: если год меньше 100, это 2000+год
        if (year < 100) {
            year += 2000; // 25 -> 2025
        } else if (year < 1900) {
            year += 1900; // старый формат
        }

        LocalDateTime ldt = LocalDateTime.of(
                year,
                header.getMonth() & 0xFF,
                header.getDay() & 0xFF,
                header.getHour() & 0xFF,
                header.getMinute() & 0xFF,
                header.getSecond() & 0xFF,
                header.getMicroSec() * 1000
        );

        return Timestamp.valueOf(ldt);
    }

    // ===== НОВЫЕ МЕТОДЫ ДЛЯ GUI =====

    /**
     * Получить список уникальных номеров трасс
     */
    public List<Integer> getTraceNumbers() throws SQLException {
        List<Integer> traces = new ArrayList<>();
        String sql = "SELECT DISTINCT trace_number FROM signal_data ORDER BY trace_number";

        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                traces.add(rs.getInt("trace_number"));
            }
        }
        return traces;
    }

    /**
     * Получить данные сигнала по номеру трассы и интервалу времени
     */
    public List<Double> getSignalData(int traceNumber, String startTime, String endTime) throws SQLException {
        List<Double> data = new ArrayList<>();
        String sql = "SELECT value FROM signal_data " +
                "WHERE trace_number = ? " +
                "AND record_time >= ?::timestamp " +
                "AND record_time <= ?::timestamp " +
                "ORDER BY record_time";

        try (Connection conn = getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setInt(1, traceNumber);
            pstmt.setString(2, startTime);
            pstmt.setString(3, endTime);

            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    data.add(rs.getDouble("value"));
                }
            }
        }
        return data;
    }

    /**
     * Получить дисперсию по номеру трассы и интервалу времени
     */
    public List<Double> getVarianceData(int traceNumber, String startTime, String endTime) throws SQLException {
        List<Double> variances = new ArrayList<>();
        String sql = "SELECT variance FROM signal_statistics " +
                "WHERE file_name IN (" +
                "   SELECT DISTINCT file_name FROM signal_data " +
                "   WHERE trace_number = ? " +
                "   AND record_time >= ?::timestamp " +
                "   AND record_time <= ?::timestamp" +
                ") " +
                "ORDER BY second_number";

        try (Connection conn = getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setInt(1, traceNumber);
            pstmt.setString(2, startTime);
            pstmt.setString(3, endTime);

            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    variances.add(rs.getDouble("variance"));
                }
            }
        }
        return variances;
    }

    /**
     * Получить sample rate для трассы
     */
    public int getSampleRate(int traceNumber) throws SQLException {
        String sql = "SELECT DISTINCT " +
                "EXTRACT(EPOCH FROM (MAX(record_time) - MIN(record_time))) / COUNT(*) as rate " +
                "FROM signal_data WHERE trace_number = ?";

        try (Connection conn = getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setInt(1, traceNumber);
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    return (int)(1.0 / rs.getDouble("rate"));
                }
            }
        }
        return 1000; // default
    }

    /**
     * Очистка всех данных из таблиц (TRUNCATE)
     */
    public void clearAllData() {
        System.out.println("\n🗑️  ОЧИСТКА БАЗЫ ДАННЫХ...");

        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement()) {

            // Отключаем проверку внешних ключей на время очистки
            stmt.execute("SET session_replication_role = 'replica'");

            // Очищаем таблицы
            int deletedStats = 0;
            int deletedData = 0;

            if (tableExists(conn, "signal_statistics")) {
                stmt.execute("TRUNCATE TABLE signal_statistics CASCADE");
                System.out.println("   ✅ signal_statistics очищена");
            }

            if (tableExists(conn, "signal_data")) {
                stmt.execute("TRUNCATE TABLE signal_data CASCADE");
                System.out.println("   ✅ signal_data очищена");
            }

            // Включаем обратно проверку внешних ключей
            stmt.execute("SET session_replication_role = 'origin'");

            System.out.println("✅ База данных полностью очищена");

        } catch (SQLException e) {
            System.err.println("❌ Ошибка при очистке БД: " + e.getMessage());
        }
    }

    /**
     * Проверка существования таблицы (добавьте если нет)
     */
    private boolean tableExists(Connection conn, String tableName) {
        String sql = "SELECT EXISTS (" +
                "   SELECT FROM information_schema.tables " +
                "   WHERE table_schema = 'public' " +
                "   AND table_name = ?" +
                ")";

        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, tableName);
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getBoolean(1);
                }
            }
        } catch (SQLException e) {
            // Таблица не существует
        }
        return false;
    }


    /**
     * Получить список уникальных названий файлов (без расширения)
     */
    public List<String> getFileNames() throws SQLException {
        List<String> fileNames = new ArrayList<>();
        String sql = "SELECT DISTINCT SUBSTRING(file_name FROM '^(.*?)\\.') as base_name " +
                "FROM signal_data " +
                "ORDER BY base_name";

        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                String name = rs.getString("base_name");
                if (name != null && !name.isEmpty()) {
                    fileNames.add(name);
                }
            }
        }
        return fileNames;
    }

    /**
     * Получить номера трасс для выбранного файла
     */
    public List<Integer> getTraceNumbersForFile(String fileName) throws SQLException {
        List<Integer> traces = new ArrayList<>();
        String sql = "SELECT DISTINCT trace_number FROM signal_data " +
                "WHERE file_name LIKE ? " +
                "ORDER BY trace_number";

        try (Connection conn = getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, fileName + ".%");
            ResultSet rs = pstmt.executeQuery();
            while (rs.next()) {
                traces.add(rs.getInt("trace_number"));
            }
        }
        return traces;
    }

    /**
     * Получить данные сигнала с фильтром по файлу и трассе
     */
    public List<Double> getSignalData(String fileName, int traceNumber,
                                      String startTime, String endTime) throws SQLException {
        List<Double> data = new ArrayList<>();
        String sql = "SELECT value FROM signal_data " +
                "WHERE file_name LIKE ? " +
                "AND trace_number = ? " +
                "AND record_time >= ?::timestamp " +
                "AND record_time <= ?::timestamp " +
                "ORDER BY record_time";

        try (Connection conn = getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, fileName + ".%");
            pstmt.setInt(2, traceNumber);
            pstmt.setString(3, startTime);
            pstmt.setString(4, endTime);

            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    data.add(rs.getDouble("value"));
                }
            }
        }
        return data;
    }

    /**
     * Получить дисперсию с фильтром по файлу и трассе
     */
    public List<Double> getVarianceData(String fileName, int traceNumber,
                                        String startTime, String endTime) throws SQLException {
        List<Double> variances = new ArrayList<>();
        String sql = "SELECT s.variance FROM signal_statistics s " +
                "WHERE s.file_name LIKE ? " +
                "AND s.file_name IN (" +
                "   SELECT DISTINCT d.file_name FROM signal_data d " +
                "   WHERE d.trace_number = ? " +
                "   AND d.record_time >= ?::timestamp " +
                "   AND d.record_time <= ?::timestamp" +
                ") " +
                "ORDER BY s.second_number";

        try (Connection conn = getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, fileName + ".%");
            pstmt.setInt(2, traceNumber);
            pstmt.setString(3, startTime);
            pstmt.setString(4, endTime);

            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    variances.add(rs.getDouble("variance"));
                }
            }
        }
        return variances;
    }

    public void shutdown() {
        System.out.println("👋 Соединение закрыто");
    }
}