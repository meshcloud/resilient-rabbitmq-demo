rootProject.name = "rabbitmq-demo"

include(
    "shared:retry-infrastructure",
    "shared:outbox-events",
    "order-service",
    "shipping-service"
)
