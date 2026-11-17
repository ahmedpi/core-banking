package com.ahmed.corebanking;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.kafka.ConfluentKafkaContainer;
import org.testcontainers.mssqlserver.MSSQLServerContainer;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class AbstractIntegrationTest {

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
