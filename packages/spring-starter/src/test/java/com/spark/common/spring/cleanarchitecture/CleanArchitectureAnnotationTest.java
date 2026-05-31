package com.spark.common.spring.cleanarchitecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.spark.common.spring.cleanarchitecture.annotation.InboundAdapter;
import com.spark.common.spring.cleanarchitecture.annotation.InfrastructureAdapter;
import com.spark.common.spring.cleanarchitecture.annotation.UseCase;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;

class CleanArchitectureAnnotationTest {
    @Test
    void useCaseIsSpringServiceStereotype() {
        Service service = AnnotatedElementUtils.findMergedAnnotation(CreateOrderUseCase.class, Service.class);

        assertThat(service).isNotNull();
        assertThat(service.value()).isEqualTo("createOrderUseCase");
    }

    @Test
    void inboundAdapterIsSpringComponentStereotype() {
        Component component = AnnotatedElementUtils.findMergedAnnotation(OrderHttpAdapter.class, Component.class);

        assertThat(component).isNotNull();
        assertThat(component.value()).isEqualTo("orderHttpAdapter");
    }

    @Test
    void infrastructureAdapterIsSpringComponentStereotype() {
        Component component = AnnotatedElementUtils.findMergedAnnotation(JpaOrderRepository.class, Component.class);

        assertThat(component).isNotNull();
        assertThat(component.value()).isEqualTo("jpaOrderRepository");
    }

    @UseCase("createOrderUseCase")
    static final class CreateOrderUseCase {
    }

    @InboundAdapter("orderHttpAdapter")
    static final class OrderHttpAdapter {
    }

    @InfrastructureAdapter("jpaOrderRepository")
    static final class JpaOrderRepository {
    }
}
