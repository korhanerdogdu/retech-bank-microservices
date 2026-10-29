package com.banking.notificationservice.model;

/** How to reach an account holder — looked up from Account Service. */
public record Contact(String accountNumber, String name, String email, String phone) {
}
