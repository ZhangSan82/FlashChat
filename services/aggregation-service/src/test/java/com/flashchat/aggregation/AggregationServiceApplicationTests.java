package com.flashchat.aggregation;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@Disabled("Requires local Redis/MySQL infrastructure; covered by focused unit and integration tests elsewhere")
@SpringBootTest(classes = AggregationServiceApplication.class)
class AggregationServiceApplicationTests {

    @Test
    void contextLoads() {
    }
}
