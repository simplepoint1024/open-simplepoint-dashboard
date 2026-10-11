package com.example.permission;

@org.simplepoint.core.permission.PermissionModel(page = "extension.view")
public record ExtensionRecord(String name, String secret) {}
