package com.example.legacy;

import java.util.ArrayList;
import java.util.List;

public class UserService {
    private final List<User> userList = new ArrayList<>();

    public void addUser(User user) {
        if (user == null) {
            throw new IllegalArgumentException("User cannot be null");
        }
        if (user.getId() == null || user.getId().isEmpty()) {
            throw new IllegalArgumentException("User ID cannot be null or empty");
        }
        userList.add(user);
    }

    public User findById(String id) {
        if (id == null) {
            return null;
        }
        for (User user : userList) {
            if (id.equals(user.getId())) {
                return user;
            }
        }
        return null;
    }

    public List<User> filterAdults() {
        List<User> result = new ArrayList<>();
        for (User user : userList) {
            if (user != null && user.getAge() >= 18) {
                result.add(user);
            }
        }
        return result;
    }

    public String formatUserSummary(User user) {
        if (user == null) {
            return "N/A";
        }
        StringBuilder sb = new StringBuilder();
        sb.append(user.getUsername() != null ? user.getUsername() : "Anonymous");
        sb.append(" (");
        sb.append(user.getEmail() != null ? user.getEmail() : "no-email");
        sb.append(")");
        return sb.toString();
    }
}
