-- init_database.sql
-- Скрипт для инициализации базы данных transport_noise

-- Проверка существования базы данных
-- Выполнять отдельно от остального!

-- Создание базы данных (если не создана)
-- CREATE DATABASE noisedb;

-- Подключаемся к базе transport_noise
-- \c noisedb;

-- Удаление старых таблиц (если нужно)
-- DROP TABLE IF EXISTS signal_data CASCADE;
-- DROP TABLE IF EXISTS signal_statistics CASCADE;

-- Создание таблицы с сырыми данными
CREATE TABLE IF NOT EXISTS signal_data (
    id BIGSERIAL PRIMARY KEY,

    -- Информация о файле
    file_name TEXT NOT NULL,
    record_start_time TIMESTAMP WITH TIME ZONE NOT NULL,  -- Время начала записи из заголовка
    latitude DOUBLE PRECISION NOT NULL,                   -- Широта из заголовка
    longitude DOUBLE PRECISION NOT NULL,                  -- Долгота из заголовка

    -- Данные отсчёта
    record_time TIMESTAMP WITH TIME ZONE NOT NULL,        -- Время конкретного отсчёта
    value DOUBLE PRECISION NOT NULL,                      -- Значение отсчёта
    trace_number SMALLINT DEFAULT 1,                      -- Номер трассы

    -- Метаданные
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()     -- Время вставки записи
);

-- Создание таблицы с агрегированной статистикой
CREATE TABLE IF NOT EXISTS signal_statistics (
    id BIGSERIAL PRIMARY KEY,

    -- Информация о файле
    file_name TEXT NOT NULL,
    record_start_time TIMESTAMP WITH TIME ZONE NOT NULL,

    -- Статистика по секундам
    second_number INTEGER NOT NULL,           -- Номер секунды
    variance DOUBLE PRECISION NOT NULL,       -- Дисперсия за эту секунду
    sample_count INTEGER NOT NULL,            -- Количество отсчётов в этой секунде

    -- Координаты (дублируем для удобства запросов)
    latitude DOUBLE PRECISION NOT NULL,
    longitude DOUBLE PRECISION NOT NULL,

    -- Уникальность: один файл + одна секунда
    UNIQUE(file_name, second_number)
);

-- Создание индексов для ускорения запросов
CREATE INDEX IF NOT EXISTS idx_signal_data_file_name
    ON signal_data(file_name);

CREATE INDEX IF NOT EXISTS idx_signal_data_record_time
    ON signal_data(record_time);

CREATE INDEX IF NOT EXISTS idx_signal_data_location
    ON signal_data(latitude, longitude);

CREATE INDEX IF NOT EXISTS idx_signal_data_file_time
    ON signal_data(file_name, record_time);

CREATE INDEX IF NOT EXISTS idx_statistics_file
    ON signal_statistics(file_name);

CREATE INDEX IF NOT EXISTS idx_statistics_time
    ON signal_statistics(record_start_time);

-- Комментарии к таблицам
COMMENT ON TABLE signal_data IS 'Сырые данные отсчётов из PC-файлов';
COMMENT ON TABLE signal_statistics IS 'Агрегированная статистика (дисперсия) по секундам';

COMMENT ON COLUMN signal_data.record_start_time IS 'Время начала записи из заголовка файла';
COMMENT ON COLUMN signal_data.record_time IS 'Время конкретного отсчёта (record_start_time + offset)';
COMMENT ON COLUMN signal_data.value IS 'Значение отсчёта (raw_value * scale)';

-- Вывод информации о созданных таблицах
SELECT
    table_name,
    (SELECT COUNT(*) FROM information_schema.columns WHERE table_name = t.table_name) as columns_count
FROM information_schema.tables t
WHERE table_schema = 'public'
    AND table_type = 'BASE TABLE'
    AND table_name IN ('signal_data', 'signal_statistics');