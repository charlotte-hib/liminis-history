package dev.wholivedwhen.support

import org.springframework.http.HttpStatus
import org.springframework.web.ErrorResponse
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

// What the services of every app throw when a request cannot be served, without knowing about HTTP. ProblemHandler
// answers each with its status, as RFC 9457 Problem Details: the message becomes the detail.

/** Nothing has this id. */
class NotFoundException(message: String) : RuntimeException(message)

/** The request does not hold together: what the contract cannot say, such as no year 0. */
class InvalidRequestException(message: String) : RuntimeException(message)

/** The request conflicts with the current state: the claim exists already, a run is still running. */
class ConflictException(message: String) : RuntimeException(message)

@RestControllerAdvice
class ProblemHandler {

    @ExceptionHandler
    fun notFound(e: NotFoundException): ErrorResponse = ErrorResponse.create(e, HttpStatus.NOT_FOUND, e.message!!)

    @ExceptionHandler
    fun invalid(e: InvalidRequestException): ErrorResponse = ErrorResponse.create(e, HttpStatus.BAD_REQUEST, e.message!!)

    @ExceptionHandler
    fun conflict(e: ConflictException): ErrorResponse = ErrorResponse.create(e, HttpStatus.CONFLICT, e.message!!)
}
