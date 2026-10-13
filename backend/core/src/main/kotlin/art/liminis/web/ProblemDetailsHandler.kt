package art.liminis.web

import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.http.HttpStatus
import org.springframework.web.ErrorResponse
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import art.liminis.support.ConflictException
import art.liminis.support.InvalidRequestException
import art.liminis.support.NotFoundException
import art.liminis.support.StaleException

/**
 * Answers the services' exceptions (support/DomainExceptions.kt) with their HTTP status, as RFC 9457 Problem Details,
 * in every app that uses core: the exception's message becomes the detail.
 */
@RestControllerAdvice
class ProblemDetailsHandler {

    @ExceptionHandler
    fun notFound(e: NotFoundException): ErrorResponse = ErrorResponse.create(e, HttpStatus.NOT_FOUND, e.message!!)

    @ExceptionHandler
    fun invalid(e: InvalidRequestException): ErrorResponse = ErrorResponse.create(e, HttpStatus.BAD_REQUEST, e.message!!)

    @ExceptionHandler
    fun conflict(e: ConflictException): ErrorResponse = ErrorResponse.create(e, HttpStatus.CONFLICT, e.message!!)

    @ExceptionHandler
    fun stale(e: StaleException): ErrorResponse = ErrorResponse.create(e, HttpStatus.PRECONDITION_FAILED, e.message!!)

    /** Two writes raced past the version check: the second one fails when Hibernate writes it, as if it were stale. */
    @ExceptionHandler
    fun raced(e: OptimisticLockingFailureException): ErrorResponse =
        ErrorResponse.create(e, HttpStatus.PRECONDITION_FAILED, "It changed while this request was being served: read it again")
}
