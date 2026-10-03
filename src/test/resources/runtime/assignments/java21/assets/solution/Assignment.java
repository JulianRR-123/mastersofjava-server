public class Assignment {
    record Answer(boolean value) {}

    public boolean run() {
        Object answer = new Answer(true);
        return switch (answer) {
            case Answer(boolean value) -> value;
            default -> false;
        };
    }
}
