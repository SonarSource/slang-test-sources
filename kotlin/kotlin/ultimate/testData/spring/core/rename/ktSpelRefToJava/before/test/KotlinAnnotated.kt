package test

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Configuration

@Configuration
open class KotlinAnnotated {
    @Value("#{/*rename*/buildBeanJ.value + 1}") private var newValue: Int = 0
}