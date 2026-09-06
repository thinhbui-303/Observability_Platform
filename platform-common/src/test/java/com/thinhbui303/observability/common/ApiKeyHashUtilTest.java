package com.thinhbui303.observability.common;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class ApiKeyHashUtilTest {

    @Test
    void hash_ShouldReturnSha256LowercaseHex_NoSalt() {
        assertThat(ApiKeyHashUtil.hash("abc")).isEqualTo(
                "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
        assertThat(ApiKeyHashUtil.hash("test_key_123")).isEqualTo(
                "1f8e8c97805e4ad56c611029fbba4c04dab40bf05d18c46655696357705cc136");
    }
}