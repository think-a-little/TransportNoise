package org.example.transport_noise;

import org.example.transport_noise.service.DatabaseService;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

public class TestConnection {
    public static void main(String[] args) {
        String url = "jdbc:postgresql://localhost:5432/noisedb";
        String user = "postgres";
        String password = "32676";
        DatabaseService db = DatabaseService.getInstance();

        // Удалить все таблицы
//        db.dropTables();

        try {
            Class.forName("org.postgresql.Driver");

            try (Connection conn = DriverManager.getConnection(url, user, password)) {
                System.out.println("✅ Подключение успешно!");

                try (Statement stmt = conn.createStatement();
                     ResultSet rs = stmt.executeQuery(
                             "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'")) {

                    System.out.println("\n📊 Таблицы в базе noisedb:");
                    while (rs.next()) {
                        System.out.println("   • " + rs.getString("table_name"));
                    }
                }

                // Проверим количество записей
                try (Statement stmt = conn.createStatement();
                     ResultSet rs = stmt.executeQuery(
                             "SELECT COUNT(*) FROM signal_statistics")) {
                    if (rs.next()) {
                        System.out.println("\n📊 Записей в signal_statistics: " + rs.getInt(1));
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("❌ Ошибка: " + e.getMessage());
        }
    }
}