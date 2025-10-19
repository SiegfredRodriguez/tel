package dev.siegfred.tel.client;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.Map;

/**
 * Feign client for calling the next service in the chain.
 * The URL is dynamically configured via the service.next.url property.
 * Feign automatically propagates trace context through HTTP headers.
 * Only created when service.next.url property is set.
 */
@FeignClient(name = "next-service", url = "${service.next.url}")
@ConditionalOnProperty(name = "service.next.url")
public interface ChainServiceClient {

    /**
     * Call the /api/chain endpoint on the next service.
     *
     * @param data the data to pass through the chain
     * @return response from the next service
     */
    @GetMapping("/api/chain")
    Map<String, Object> chain(@RequestParam("data") String data);
}
