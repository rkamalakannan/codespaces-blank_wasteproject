package cloudcode.krakenfutures.leadlag.signal;

public class BtcSignal {
    private final SignalType type;
    private final double magnitude;
    private final long timestamp;

    public BtcSignal(SignalType type, double magnitude, long timestamp) {
        this.type = type;
        this.magnitude = magnitude;
        this.timestamp = timestamp;
    }

    public SignalType getType() { return type; }
    public double getMagnitude() { return magnitude; }
    public long getTimestamp() { return timestamp; }

    @Override
    public String toString() {
        return "BtcSignal{" +
                "type=" + type +
                ", magnitude=" + String.format("%.4f%%", magnitude) +
                ", timestamp=" + timestamp +
                '}';
    }
}
