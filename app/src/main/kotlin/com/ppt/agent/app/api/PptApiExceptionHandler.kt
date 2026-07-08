package com.ppt.agent.app.api

import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

@RestControllerAdvice(basePackageClasses = [PptApiController::class])
class PptApiExceptionHandler {

  @ExceptionHandler(IllegalArgumentException::class)
  fun onBadRequest(e: IllegalArgumentException): ResponseEntity<Map<String, Any?>> =
      ResponseEntity.status(HttpStatus.BAD_REQUEST).body(mapOf("error" to "bad_request", "message" to e.message))
}
