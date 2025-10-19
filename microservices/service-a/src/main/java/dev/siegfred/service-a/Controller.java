package dev.siegfred.servicea;

import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestClient;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api")
public class Controller {

    private final RestClient restClient;

    public Controller(RestClient restClient) {
        this.restClient = restClient;
    }

    @GetMapping("/process")
    public Map<String, Object> process(@RequestParam(defaultValue = "data") String data) {
        log.info("[service-a] Received request with data: {}", data);

        Map<String, Object> response = new HashMap<>();
        response.put("service", "service-a");
        response.put("data", data);
        response.put("timestamp", System.currentTimeMillis());

        // Call next service in the chain
        try {
            log.info("[service-a] Calling next service: service-b");
            String nextServiceUrl = "http://service-b:8082/api/process?data=" + data;
            Map<String, Object> nextResponse = restClient.get()
                .uri(nextServiceUrl)
                .retrieve()
                .body(Map.class);
            response.put("next", nextResponse);
            log.info("[service-a] Successfully received response from service-b");
        } catch (Exception e) {
            log.error("[service-a] Error calling next service", e);
            response.put("error", "Failed to call service-b: " + e.getMessage());
        }

        log.info("[service-a] Returning response");
        return response;
    }
}
