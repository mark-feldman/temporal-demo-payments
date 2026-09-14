package com.example.payouts.api

import com.example.payouts.simulation.SimulationConfig
import com.example.payouts.simulation.SimulationRunner
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/demo-api/simulation")
@ConditionalOnBean(SimulationRunner::class)
class SimulationController(private val runner: SimulationRunner) {

    @PostMapping("/start")
    fun start(@RequestBody config: SimulationConfig) = runner.start(config)

    @PostMapping("/stop")
    fun stop() = runner.stop()

    @GetMapping("/status")
    fun status() = runner.status()
}
