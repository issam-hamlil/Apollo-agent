package com.example.legacy;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class AsyncDataLoader {

    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    public interface DataCallback<T> {
        void onSuccess(T result);
        void onError(Exception e);
    }

    public void loadUserData(String userId, DataCallback<User> callback) {
        executor.submit(() -> {
            if (userId == null || userId.trim().isEmpty()) {
                if (callback != null) {
                    callback.onError(new IllegalArgumentException("User ID cannot be null or empty"));
                }
                return;
            }

            try {
                User user = new User(userId, "User_" + userId, userId + "@example.com", 30);
                if (callback != null) {
                    callback.onSuccess(user);
                }
            } catch (Exception e) {
                if (callback != null) {
                    callback.onError(e);
                }
            }
        });
    }

    public void shutdown() {
        executor.shutdown();
    }
}
