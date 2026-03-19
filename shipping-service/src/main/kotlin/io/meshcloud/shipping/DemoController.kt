package io.meshcloud.shipping

import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.concurrent.atomic.AtomicInteger

@RestController
@RequestMapping("/demo")
class DemoController {

    /** Counter: the next N processing attempts by [OrderCreatedListener] will fail. */
    val failRemainingAttempts = AtomicInteger(0)

    /**
     * ```
     * curl -s -X POST http://localhost:8081/demo/fail-next-message
     * ```
     */
    @PostMapping("/fail-next-message")
    fun failNextMessage(): ResponseEntity<Map<String, String>> {
        failRemainingAttempts.set(6)
        return ResponseEntity.ok(mapOf("message" to "Next 6 processing attempts will fail — spring retry + retry queue ladder activated"))
    }
}
