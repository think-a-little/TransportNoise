package org.example.transport_noise.model;

public class FileHeader {
    private short id;           // 'PC'
    private byte[] reserv;      // 4 байта
    private float lat;          // широта
    private float lon;          // долгота
    private double scale;       // коэффициент пересчета
    private byte year;
    private byte month;
    private byte day;
    private byte hour;
    private byte minute;
    private byte second;
    private int microSec;       // поправка в микросекундах
    private short samplRate;    // частота дискретизации в Гц
    private int samplNum;       // число отсчетов
    private short samplType;    // тип отсчета
    private byte trNum;         // номер трассы
    private byte reserved;

    public FileHeader(short id, byte[] reserv, float lat, float lon, double scale,
                      byte year, byte month, byte day, byte hour, byte minute, byte second,
                      int microSec, short samplRate, int samplNum, short samplType,
                      byte trNum, byte reserved) {
        this.id = id;
        this.reserv = reserv;
        this.lat = lat;
        this.lon = lon;
        this.scale = scale;
        this.year = year;
        this.month = month;
        this.day = day;
        this.hour = hour;
        this.minute = minute;
        this.second = second;
        this.microSec = microSec;
        this.samplRate = samplRate;
        this.samplNum = samplNum;
        this.samplType = samplType;
        this.trNum = trNum;
        this.reserved = reserved;
    }

    public short getId() { return id; }
    public float getLat() { return lat; }
    public float getLon() { return lon; }
    public double getScale() { return scale; }
    public byte getYear() { return year; }
    public byte getMonth() { return month; }
    public byte getDay() { return day; }
    public byte getHour() { return hour; }
    public byte getMinute() { return minute; }
    public byte getSecond() { return second; }
    public int getMicroSec() { return microSec; }
    public short getSamplRate() { return samplRate; }
    public int getSamplNum() { return samplNum; }
    public short getSamplType() { return samplType; }
    public byte getTrNum() { return trNum; }

    public String getDateTime() {
        int y = year & 0xFF;
        // Если год меньше 100, считаем что это 2000+год
        if (y < 100) {
            y += 2000;
        } else if (y < 1900) {
            y += 1900;
        }

        return String.format("%04d-%02d-%02d %02d:%02d:%02d.%06d",
                y, month & 0xFF, day & 0xFF,
                hour & 0xFF, minute & 0xFF, second & 0xFF, microSec);
    }

    public String getSampleTypeString() {
        switch (samplType) {
            case 0x0002: return "short (16-bit знаковый)";
            case 0x0004: return "int (32-bit знаковый)";
            case 0x1004: return "float (32-bit плавающая)";
            case 0x1008: return "double (64-bit)";
            default: return "unknown (0x" + Integer.toHexString(samplType & 0xFFFF) + ")";
        }
    }

    public int getSampleSize() {
        switch (samplType) {
            case 0x0002: return 2; // short
            case 0x0004: return 4; // int
            case 0x1004: return 4; // float
            case 0x1008: return 8; // double
            default: return 0;
        }
    }

    @Override
    public String toString() {
        int y = year & 0xFF;
        if (y < 100) {
            y += 2000;
        } else if (y < 1900) {
            y += 1900;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("=== HEADER ===\n");
        sb.append("ID: ").append((char)(id & 0xFF)).append((char)((id >> 8) & 0xFF)).append("\n");
        sb.append("Lat/Lon: ").append(lat).append(" / ").append(lon).append("\n");
        sb.append("Scale: ").append(scale).append("\n");
        sb.append("DateTime: ").append(String.format("%04d-%02d-%02d %02d:%02d:%02d.%06d",
                y, month & 0xFF, day & 0xFF,
                hour & 0xFF, minute & 0xFF, second & 0xFF, microSec)).append("\n");
        sb.append("SampleRate: ").append(samplRate & 0xFFFF).append(" Hz\n");
        sb.append("SamplesNum: ").append(samplNum).append("\n");
        sb.append("SampleType: ").append(getSampleTypeString()).append("\n");
        sb.append("TraceNum: ").append(trNum & 0xFF).append("\n");
        return sb.toString();
    }
}