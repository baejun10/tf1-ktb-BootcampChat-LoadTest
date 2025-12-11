package com.ktb.chatapp.config;

import java.net.URI;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

@Configuration
public class S3Config {

    @Bean
    public S3Client s3Client(
            @Value("${storage.s3.region}") String region,
            @Value("${storage.s3.endpoint:}") String endpoint,
            @Value("${storage.s3.path-style-enabled:false}") boolean pathStyleEnabled,
            @Value("${storage.s3.access-key-id}") String accessKeyId,
            @Value("${storage.s3.secret-access-key}") String secretAccessKey
    ) {
        AwsBasicCredentials credentials = AwsBasicCredentials.create(accessKeyId, secretAccessKey);

        ClientOverrideConfiguration clientConfig = ClientOverrideConfiguration.builder()
                .apiCallTimeout(Duration.ofSeconds(10))
                .apiCallAttemptTimeout(Duration.ofSeconds(5))
                .build();

        S3ClientBuilder builder = S3Client.builder()
                .credentialsProvider(StaticCredentialsProvider.create(credentials))
                .region(Region.of(region))
                .overrideConfiguration(clientConfig);

        if (StringUtils.hasText(endpoint)) {
            builder.endpointOverride(URI.create(endpoint));
        }

        S3Configuration.Builder serviceConfig = S3Configuration.builder()
                .checksumValidationEnabled(false);
        if (pathStyleEnabled) {
            serviceConfig.pathStyleAccessEnabled(true);
        }
        builder.serviceConfiguration(serviceConfig.build());

        return builder.build();
    }

    @Bean
    public S3Presigner s3Presigner(
            @Value("${storage.s3.region}") String region,
            @Value("${storage.s3.endpoint:}") String endpoint,
            @Value("${storage.s3.access-key-id}") String accessKeyId,
            @Value("${storage.s3.secret-access-key}") String secretAccessKey
    ) {
        AwsBasicCredentials credentials = AwsBasicCredentials.create(accessKeyId, secretAccessKey);

        S3Presigner.Builder builder = S3Presigner.builder()
                .region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(credentials));

        if (StringUtils.hasText(endpoint)) {
            builder.endpointOverride(URI.create(endpoint));
        }

        return builder.build();
    }
}
