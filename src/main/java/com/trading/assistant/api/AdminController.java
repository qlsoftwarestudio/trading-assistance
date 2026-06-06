package com.trading.assistant.api;

import com.trading.assistant.admin.AdminService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/admin")
@Tag(name = "Admin API", description = "Bot operations, strategy config, and system health for the super-admin panel")
public class AdminController {

    @Autowired
    private AdminService adminService;

    @GetMapping("/config")
    @Operation(summary = "Get strategy configuration", description = "Returns all current trading strategy and system configuration values")
    public ResponseEntity<Map<String, Object>> getStrategyConfig() {
        return ResponseEntity.ok(adminService.getStrategyConfig());
    }

    @GetMapping("/health")
    @Operation(summary = "Get bot health", description = "Returns strategy state, open trades count, last signal time, and connection status")
    public ResponseEntity<Map<String, Object>> getBotHealth() {
        return ResponseEntity.ok(adminService.getBotHealth());
    }
}
