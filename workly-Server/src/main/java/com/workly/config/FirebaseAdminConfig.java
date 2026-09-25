package com.workly.config;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.io.InputStream;

@Component
@Slf4j
public class FirebaseAdminConfig {

    @Value("${firebase.admin.credentials:}")
    private Resource credentialsResource;

    @PostConstruct
    public void initializeFirebaseApp() {
        if (!FirebaseApp.getApps().isEmpty()) {
            log.debug("FirebaseAdminConfig: FirebaseApp already initialized");
            return;
        }

        if (credentialsResource == null || !credentialsResource.exists()) {
            log.warn("FirebaseAdminConfig: firebase.admin.credentials is missing. FCM sends will be skipped.");
            return;
        }

        try (InputStream inputStream = credentialsResource.getInputStream()) {
            FirebaseOptions options = FirebaseOptions.builder()
                    .setCredentials(GoogleCredentials.fromStream(inputStream))
                    .build();
            FirebaseApp.initializeApp(options);
            log.info("FirebaseAdminConfig: FirebaseApp initialized successfully");
        } catch (Exception e) {
            log.error("FirebaseAdminConfig: Failed to initialize FirebaseApp", e);
        }
    }
}
