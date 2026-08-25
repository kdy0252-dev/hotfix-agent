package com.example.myagent.dashboard.adapter.in.web;

import com.example.myagent.dashboard.application.port.in.DashboardUseCaseException;
import jakarta.validation.ConstraintViolationException;
import org.jmolecules.architecture.hexagonal.Adapter;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.servlet.ModelAndView;

@Adapter
@ControllerAdvice(assignableTypes = DashboardController.class)
public class DashboardExceptionHandler {
    @ExceptionHandler(DashboardUseCaseException.class)
    @ResponseStatus(HttpStatus.UNPROCESSABLE_CONTENT)
    public ModelAndView handleDashboardFailure(DashboardUseCaseException exception) {
        return error(exception.code(), exception.getMessage());
    }

    @ExceptionHandler(ConstraintViolationException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ModelAndView handleValidationFailure(ConstraintViolationException exception) {
        return error("INVALID_UI_REQUEST", "입력값을 다시 확인해주세요.");
    }

    private ModelAndView error(String code, String message) {
        var modelAndView = new ModelAndView("dashboard/fragments/error");
        modelAndView.addObject("errorCode", code);
        modelAndView.addObject("errorMessage", message);
        return modelAndView;
    }
}
