package com.ahmed.corebanking;

import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.kafka.ConfluentKafkaContainer;
import org.testcontainers.mssqlserver.MSSQLServerContainer;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {"spring.ai.ollama.base-url=http://localhost:0", "spring.ai.ollama.init.pull-model-strategy=never", "spring.ai.retry.max-attempts=1"})
public abstract class AbstractIntegrationTest {

    @MockitoBean
    private OllamaChatModel ollamaChatModel;

    @ServiceConnection
    static final MSSQLServerContainer SQL_SERVER =
            new MSSQLServerContainer(DockerImageName.parse("mcr.microsoft.com/mssql/server:2022-latest"))
                    .acceptLicense()
                    .withPassword("Password123!");

    @ServiceConnection
    static final ConfluentKafkaContainer KAFKA =
            new ConfluentKafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.1"));

    static {
        SQL_SERVER.start();
        KAFKA.start();
    }
}
