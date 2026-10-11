import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
public class ReadProbe {
    public static void main(String[] args) throws Exception {
        String parent = Files.readString(Path.of("/proc/1/environ"), StandardCharsets.UTF_8);
        if (!parent.contains("MOJ_REVIEW_MARKER=disposable-marker")) throw new AssertionError("Marker missing");
        System.out.println("CONFIRMED: bundled policy permits reading a harmless marker from parent process environment");
    }
}
