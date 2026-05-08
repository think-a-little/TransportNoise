package org.example.transport_noise;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

public class DatabaseTest {
    public static void main(String[] args) {
        String url = "jdbc:postgresql://localhost:5432/noisedb";
        String user = "postgres";
        String password = "32676"; // ваш пароль

        try {
            Class.forName("org.postgresql.Driver");

            try (Connection conn = DriverManager.getConnection(url, user, password)) {
                System.out.println("✅ Подключение к noisedb успешно!");

                // Проверяем существование таблиц
                try (Statement stmt = conn.createStatement();
                     ResultSet rs = stmt.executeQuery(
                             "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'")) {

                    System.out.println("\n📊 Таблицы в базе данных:");
                    while (rs.next()) {
                        System.out.println("   • " + rs.getString("table_name"));
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("❌ Ошибка: " + e.getMessage());
        }
    }
}