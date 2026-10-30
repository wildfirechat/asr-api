package cn.wildfirechat.asr.utilis;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.net.URL;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AudioUrlValidatorTest {

    private AudioUrlValidator validator;

    @BeforeEach
    void setUp() {
        validator = new AudioUrlValidator();
        ReflectionTestUtils.setField(validator, "blockPrivateNetworks", true);
        ReflectionTestUtils.setField(validator, "allowedHostsConfig", "minio.internal,10.0.0.8:9000");
        validator.init();
    }

    @Test
    void rejectsNonHttpProtocols() {
        assertThrows(IOException.class, () -> validator.validate(new URL("file:///etc/passwd")));
        assertThrows(IOException.class, () -> validator.validate(new URL("jar:file:/tmp/a.jar!/x")));
        assertThrows(IOException.class, () -> validator.validate(new URL("ftp://example.com/a.wav")));
    }

    @Test
    void rejectsPrivateAndMetadataAddresses() {
        assertThrows(IOException.class, () -> validator.validate(new URL("http://127.0.0.1:8080/a.wav")));
        assertThrows(IOException.class, () -> validator.validate(new URL("http://192.168.1.10/a.wav")));
        assertThrows(IOException.class, () -> validator.validate(new URL("http://169.254.169.254/latest/meta-data/")));
        assertThrows(IOException.class, () -> validator.validate(new URL("http://100.100.100.200/")));
    }

    @Test
    void allowsPublicAddress() throws Exception {
        URL url = new URL("https://example.com/a.wav");
        assertEquals(url, validator.validate(url));
    }

    @Test
    void allowsConfiguredPrivateHost() throws Exception {
        URL url = new URL("http://10.0.0.8:9000/bucket/a.wav");
        assertEquals(url, validator.validate(url));
    }
}
