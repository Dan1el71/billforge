package com.billforge;

import com.billforge.app.BillforgeApplication;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(classes = BillforgeApplication.class)
@ActiveProfiles("test")
class BillforgeApplicationTests {

    @Test
    void contextLoads() {
    }

}
