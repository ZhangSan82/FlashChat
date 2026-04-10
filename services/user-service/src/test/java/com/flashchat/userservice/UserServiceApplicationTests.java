package com.flashchat.userservice;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Disabled;
import org.springframework.boot.test.context.SpringBootTest;

@Disabled("Requires local Redis/MySQL infrastructure; covered by focused unit and integration tests elsewhere")
@SpringBootTest(classes = UserServiceApplication.class)
class UserServiceApplicationTests {

    @Test
    void contextLoads() {
    }

}
