package nl.moj.common.config.properties;

import java.nio.file.Path;
import java.util.List;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

import nl.moj.common.util.JavaVersionUtil;

public class LanguagesTest {

    @Test
    public void shouldDetectJava21Runtime() {
        Assertions.assertThat(JavaVersionUtil.getRuntimeMajorVersion(currentJdk())).isEqualTo(21);
    }

    @Test
    public void shouldSelectConfiguredJava21ForExistingAndNewAssignments() {
        Languages languages = new Languages();
        Languages.JavaVersion jdk = currentJdk();
        languages.setJavaVersions(List.of(jdk));
        Assertions.assertThat(languages.getJavaVersion(17)).isSameAs(jdk);
        Assertions.assertThat(languages.getJavaVersion(21)).isSameAs(jdk);
    }

    @Test
    public void shouldFallBackToJavaHomeForExistingAndNewAssignments() {
        Assertions.assertThat(System.getenv("JAVA_HOME")).isNotBlank();
        Languages languages = new Languages();
        for (int requested : List.of(17, 21)) {
            Languages.JavaVersion selected = languages.getJavaVersion(requested);
            Assertions.assertThat(selected.getName()).isEqualTo("fallback");
            Assertions.assertThat(selected.getVersion()).isEqualTo(21);
            Assertions.assertThat(selected.getCompiler()).isEqualTo(Path.of(System.getenv("JAVA_HOME"), "bin", "javac"))
                    .isExecutable();
            Assertions.assertThat(selected.getRuntime()).isEqualTo(Path.of(System.getenv("JAVA_HOME"), "bin", "java"))
                    .isExecutable();
        }
    }

    @Test
    public void shouldRejectAssignmentsRequiringANewerJdk() {
        Assertions.assertThatThrownBy(() -> new Languages().getJavaVersion(22))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("No java runtime available for version 22");
    }

    private Languages.JavaVersion currentJdk() {
        Languages.JavaVersion jdk = new Languages.JavaVersion();
        jdk.setVersion(21);
        jdk.setName("Java 21");
        jdk.setCompiler(Path.of(System.getProperty("java.home"), "bin", "javac"));
        jdk.setRuntime(Path.of(System.getProperty("java.home"), "bin", "java"));
        return jdk;
    }
}
