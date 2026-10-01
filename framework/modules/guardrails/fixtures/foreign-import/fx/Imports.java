package fx;

import java.util.List;
import module java.base;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import com.example.lib.*;
import module org.example.lib;

// Fixture: imports from outside the JDK. java.util.List and java.base are negative controls.
class Imports {

    @Test
    void test() {
        assertThat(List.of()).isEmpty();
    }
}
