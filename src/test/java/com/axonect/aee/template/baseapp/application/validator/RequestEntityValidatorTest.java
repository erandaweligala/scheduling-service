package com.axonect.aee.template.baseapp.application.validator;

import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.when;

class RequestEntityValidatorTest {

    @Mock
    private Validator validator;

    @InjectMocks
    private RequestEntityValidator requestEntityValidator;

    @Test
    void testValidate_NoErrors() {
        MockitoAnnotations.initMocks(this);
        RequestEntityInterface requestEntity = new RequestEntityImplementation();
        when(validator.validate(requestEntity)).thenReturn(Collections.emptySet());
        assertDoesNotThrow(() -> requestEntityValidator.validate(requestEntity));


    }

    private static class RequestEntityImplementation implements RequestEntityInterface {
        // Implement or provide necessary methods and fields for testing
    }
}
