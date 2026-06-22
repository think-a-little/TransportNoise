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
        System.out.println("\n🔧 Проверка и создание таблиц...");

        try (Statement stmt = conn.createStatement()) {

            // 1. Таблица signal_data
            if (!tableExists(conn, "signal_data")) {
                System.out.println("   📊 Создание таблицы signal_data...");

                String createSignalData =
                        "CREATE TABLE signal_data (" +
                                "    id BIGSERIAL PRIMARY KEY," +
                                "    file_name TEXT NOT NULL," +
                                "    record_start_time TIMESTAMP WITH TIME ZONE NOT NULL," +
                                "    latitude DOUBLE PRECISION NOT NULL," +
                                "    longitude DOUBLE PRECISION NOT NULL," +
                                "    record_time TIMESTAMP WITH TIME ZONE NOT NULL," +
                                "    value DOUBLE PRECISION NOT NULL," +
                                "    trace_number SMALLINT DEFAULT 1," +
                                "    sample_count INTEGER DEFAULT 0," +
                                "    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()" +
                                ")";
                stmt.execute(createSignalData);
                System.out.println("   ✅ Таблица signal_data создана");
            } else {
                System.out.println("   ✅ Таблица signal_data существует");
                // Добавляем столбец sample_count, если его нет в старой таблице
                try {
                    stmt.execute("ALTER TABLE signal_data ADD COLUMN IF NOT EXISTS sample_count INTEGER DEFAULT 0");
                    System.out.println("   ✅ Столбец sample_count добавлен");
                } catch (SQLException e) {
                    System.out.println("   ℹ️  Столбец sample_count уже существует");
                }
            }

            // 2. Таблица signal_statistics
            try {
                stmt.execute("DROP TABLE IF EXISTS signal_statistics CASCADE");
            } catch (SQLException e) {}

            System.out.println("   📊 Создание таблицы signal_statistics...");

            String createSignalStatistics =
                    "CREATE TABLE signal_statistics (" +
                            "    id BIGSERIAL PRIMARY KEY," +
                            "    file_name TEXT NOT NULL," +
                            "    second_number INTEGER NOT NULL," +
                            "    variance DOUBLE PRECISION NOT NULL," +
                            "    UNIQUE(file_name, second_number)" +
                            ")";
            stmt.execute(createSignalStatistics);
            System.out.println("   ✅ Таблица signal_statistics создана");

            // Создаем индексы
            System.out.println("   📊 Создание индексов...");

            try {
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_signal_data_file_name ON signal_data(file_name)");
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_signal_data_record_time ON signal_data(record_time)");
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_signal_data_location ON signal_data(latitude, longitude)");
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_signal_data_trace_time ON signal_data(trace_number, record_time)");
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_statistics_file ON signal_statistics(file_name)");
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_statistics_second ON signal_statistics(second_number)");

                System.out.println("   ✅ Индексы созданы");
            } catch (SQLException e) {
                System.out.println("   ℹ️  Индексы уже существуют");
            }

            // Финальная проверка
            System.out.println("\n📊 Статус таблиц:");
            System.out.println("   signal_data: " + (tableExists(conn, "signal_data") ? "✅" : "❌"));
            System.out.println("   signal_statistics: " + (tableExists(conn, "signal_statistics") ? "✅" : "❌"));
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

        // Шаг 1: сохраняем сырые данные в signal_data
        try (Connection conn = getConnection()) {
            conn.setAutoCommit(false);
            try {
                int savedSamples = saveRawData(conn, result);
                conn.commit();
                System.out.println("   ✅ Отсчётов сохранено: " + savedSamples);
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            }
        }

        // Шаг 2: сохраняем статистику в signal_statistics (отдельная транзакция)
        try (Connection conn = getConnection()) {
            conn.setAutoCommit(false);
            try {
                int savedStats = saveStatistics(conn, result);
                conn.commit();
                System.out.println("   ✅ Статистика сохранена: " + savedStats);
            } catch (SQLException e) {
                System.err.println("   ❌ ОШИБКА СТАТИСТИКИ: " + e.getMessage());
                conn.rollback();
                throw e;
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

        // Обновляем sample_count для первой записи этого файла
        String updateSamples = "UPDATE signal_data SET sample_count = ? " +
                "WHERE file_name = ? AND id = (SELECT MIN(id) FROM signal_data WHERE file_name = ?)";
        try (PreparedStatement pstmt = conn.prepareStatement(updateSamples)) {
            pstmt.setInt(1, rawSamples.size());
            pstmt.setString(2, result.getFileName());
            pstmt.setString(3, result.getFileName());
            pstmt.executeUpdate();
        }

        return totalInserted;
    }

    private int saveStatistics(Connection conn, FileAnalysisResult result) throws SQLException {
        List<Double> variances = result.getVariances();
        int totalInserted = 0;

        String sql = "INSERT INTO signal_statistics (file_name, second_number, variance) " +
                "VALUES (?, ?, ?) " +
                "ON CONFLICT (file_name, second_number) DO UPDATE SET variance = EXCLUDED.variance";

        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            for (int i = 0; i < variances.size(); i++) {
                pstmt.setString(1, result.getFileName());
                pstmt.setInt(2, i + 1);
                pstmt.setDouble(3, variances.get(i));
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
    public List<Double> getVarianceData(String fileName, int traceNumber,
                                        String startTime, String endTime) throws SQLException {
        List<Double> variances = new ArrayList<>();

        // Формируем точное имя файла: номер трассы - 1 = суффикс
        String exactFileName = String.format("%s.%02d", fileName, traceNumber - 1);

        String sql = "SELECT s.variance FROM signal_statistics s " +
                "WHERE s.file_name = ? " +
                "ORDER BY s.second_number";

        try (Connection conn = getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, exactFileName);
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

            // Временно отключаем проверку внешних ключей
            stmt.execute("SET session_replication_role = 'replica'");

            // Очищаем таблицы (порядок не важен)
            stmt.execute("TRUNCATE TABLE signal_statistics");
            stmt.execute("TRUNCATE TABLE signal_data");
            stmt.execute("TRUNCATE TABLE THREE_COMPONENT_ANALYSIS");

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
     * Сигнал с осью времени в секундах от начала выборки (для графиков STA/LTA по одной трассе).
     */
    public static final class TimedSignal {
        public final List<Double> timeSeconds;
        public final List<Double> values;

        public TimedSignal(List<Double> timeSeconds, List<Double> values) {
            this.timeSeconds = timeSeconds;
            this.values = values;
        }
    }

    public TimedSignal getSignalDataTimed(String fileName, int traceNumber,
                                          String startTime, String endTime) throws SQLException {
        List<Double> times = new ArrayList<>();
        List<Double> values = new ArrayList<>();
        String sql = "WITH sel AS ( "
                + "  SELECT record_time, value FROM signal_data "
                + "  WHERE file_name LIKE ? AND trace_number = ? "
                + "    AND record_time >= ?::timestamp AND record_time <= ?::timestamp "
                + "), t0 AS ( SELECT MIN(record_time) AS r0 FROM sel ) "
                + "SELECT EXTRACT(EPOCH FROM (sel.record_time - t0.r0)), sel.value "
                + "FROM sel CROSS JOIN t0 ORDER BY sel.record_time";

        try (Connection conn = getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, fileName + ".%");
            pstmt.setInt(2, traceNumber);
            pstmt.setString(3, startTime);
            pstmt.setString(4, endTime);
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    times.add(rs.getDouble(1));
                    values.add(rs.getDouble(2));
                }
            }
        }
        return new TimedSignal(times, values);
    }



    /**
     * Сохранение результатов трехкомпонентного анализа
     */
    public void saveThreeComponentResult(String stationName,
                                         ThreeComponentAnalyzer.ThreeComponentResult result,
                                         double latitude, double longitude,
                                         int sampleRate) {  // ← добавьте параметр

        String sql = "INSERT INTO three_component_analysis " +
                "(station_name, timestamp, time_seconds, result_amplitude, azimuth, " +
                "incidence_angle, polarization, is_event, latitude, longitude) " +
                "VALUES (?, NOW(), ?, ?, ?, ?, ?, ?, ?, ?)";

        try (Connection conn = getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            List<Double> amplitudes = result.getResultAmplitude();
            List<Double> azimuths = result.getAzimuth();
            List<Double> incAngles = result.getIncidenceAngle();
            List<Double> polarizations = result.getPolarization();
//            int step = Math.max(1, amplitudes.size() / 10000);
int step = 1;
            for (int i = 0; i < amplitudes.size(); i += step) {
                double timeSeconds = (double) i / sampleRate;  // ← реальное время

                pstmt.setString(1, stationName);
                pstmt.setDouble(2, timeSeconds);  // ← сохраняем время
                pstmt.setDouble(3, amplitudes.get(i));
                pstmt.setDouble(4, azimuths.get(i));
                pstmt.setDouble(5, incAngles.get(i));
                pstmt.setDouble(6, polarizations.get(i));
                pstmt.setBoolean(7, result.isKeyEventSample(i));
                pstmt.setDouble(8, latitude);
                pstmt.setDouble(9, longitude);

                pstmt.addBatch();
            }

            pstmt.executeBatch();
            System.out.println("   ✅ Сохранено точек анализа: " + (amplitudes.size() / step));

        } catch (SQLException e) {
            System.err.println("   ❌ Ошибка: " + e.getMessage());
        }
    }

    public void shutdown() {
        System.out.println("👋 Соединение закрыто");
    }
}