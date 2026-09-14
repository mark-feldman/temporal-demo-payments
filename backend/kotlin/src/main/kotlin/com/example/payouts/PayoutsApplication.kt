package com.example.payouts

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

@SpringBootApplication
@EnableScheduling
class PayoutsApplication

fun main(args: Array<String>) {
    runApplication<PayoutsApplication>(*args)
}
