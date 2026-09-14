package com.example.payouts.api

import com.example.payouts.worker.WorkerSupervisor
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/demo-api/workers")
@ConditionalOnBean(WorkerSupervisor::class)
class WorkerController(private val supervisor: WorkerSupervisor) {

    @GetMapping
    fun fleet() = supervisor.fleet()

    /** SIGKILL the most recently started worker. */
    @PostMapping("/kill")
    fun kill(@RequestParam(required = false) id: Int?) = supervisor.kill(id)

    @PostMapping("/kill-all")
    fun killAll() = supervisor.killAll()

    /** The cap is applied by the supervisor, which owns the port range it agrees with. */
    @PostMapping("/scale")
    fun scale(@RequestParam count: Int) = supervisor.scaleTo(count)
}
