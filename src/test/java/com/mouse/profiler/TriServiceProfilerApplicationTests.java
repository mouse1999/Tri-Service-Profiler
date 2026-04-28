package com.mouse.profiler;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
@Disabled("Disabled due to Redis connection issues in CI - rate limiting tests cover the functionality")
class TriServiceProfilerApplicationTests {

    @Test
    void contextLoads() {
    }

}
