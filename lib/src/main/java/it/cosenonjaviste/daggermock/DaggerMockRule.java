/*
 *  Copyright 2016 Fabio Collini.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package it.cosenonjaviste.daggermock;

import org.junit.rules.MethodRule;
import org.junit.runners.model.FrameworkMethod;
import org.junit.runners.model.Statement;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.inject.Provider;

public class DaggerMockRule<C> implements MethodRule {
    private Class<C> componentClass;
    private ComponentSetter<C> componentSetter;
    private List<Object> modules = new ArrayList<>();
    private final Map<Class, List<Object>> dependencies = new HashMap<>();
    private final OverriddenObjectsMap overriddenObjectsMap = new OverriddenObjectsMap();

    public DaggerMockRule(Class<C> componentClass, Object... modules) {
        this.componentClass = componentClass;
        Collections.addAll(this.modules, modules);
    }

    public DaggerMockRule<C> set(ComponentSetter<C> componentSetter) {
        this.componentSetter = componentSetter;
        return this;
    }

    public <S> DaggerMockRule<C> provides(Class<S> originalClass, final S newObject) {
        overriddenObjectsMap.put(originalClass, newObject);
        return this;
    }

    public <S> DaggerMockRule<C> provides(Class<S> originalClass, Provider<S> provider) {
        overriddenObjectsMap.putProvider(originalClass, provider);
        return this;
    }

    public DaggerMockRule<C> addComponentDependency(Class componentClass, Object... modules) {
        dependencies.put(componentClass, Arrays.asList(modules));
        return this;
    }

    public DaggerMockRule<C> providesMock(final Class<?>... originalClasses) {
        overriddenObjectsMap.putMocks(originalClasses);
        return this;
    }

    @Override
    public Statement apply(final Statement base, FrameworkMethod method, final Object target) {
        return new Statement() {
            @Override
            public void evaluate() throws Throwable {
                MockitoAnnotations.initMocks(target);

                overriddenObjectsMap.init(target);
                overriddenObjectsMap.checkOverriddenInjectAnnotatedClass();

                ModuleOverrider moduleOverrider = new ModuleOverrider(overriddenObjectsMap);

                overriddenObjectsMap.checkOverridesInSubComponentsWithNoParameters(componentClass);

                Object componentBuilder = initComponent(componentClass, modules, moduleOverrider);

                componentBuilder = initComponentDependencies(componentBuilder, moduleOverrider);

                C component = (C) ReflectUtils.buildComponent(componentBuilder);

                component = new ComponentOverrider(moduleOverrider).override(componentClass, component);

                if (componentSetter != null) {
                    componentSetter.setComponent(component);
                }

                initInjectFromComponentFields(new ObjectWrapper<>(target), new ObjectWrapper<>(component));

                base.evaluate();

                Mockito.validateMockitoUsage();
            }
        };
    }

    private void initInjectFromComponentFields(ObjectWrapper<Object> target, ObjectWrapper<C> component) {
        List<Field> fields = target.extractAnnotatedFields(InjectFromComponent.class);
        for (Field field : fields) {
            InjectFromComponent annotation = field.getAnnotation(InjectFromComponent.class);
            Class<?>[] annotationValues = annotation.value();
            if (annotationValues.length == 0) {
                Method m = component.getMethodReturning(field.getType());
                if (m != null) {
                    Object obj = component.invokeMethod(m);
                    target.setFieldValue(field, obj);
                }
            } else {
                Class<Object> classToInject = (Class<Object>) annotationValues[0];
                ObjectWrapper<Object> obj = ObjectWrapper.newInstance(classToInject);
                Method injectMethod = component.getMethodWithParameter(classToInject);
                if (injectMethod != null) {
                    component.invokeMethod(injectMethod, obj.getValue());
                    for (int i = 1; i < annotationValues.length; i++) {
                        Class<?> c = annotationValues[i];
                        obj = new ObjectWrapper<>(obj.getFieldValue(c));
                    }
                    Object fieldValue = obj.getFieldValue(field.getType());
                    target.setFieldValue(field, fieldValue);
                }
            }
        }
    }

    private Object initComponent(Class componentClass, List<Object> modules, ModuleOverrider moduleOverrider) {
        Class<Object> daggerComponent = ReflectUtils.getDaggerComponentClass(componentClass);
        ObjectWrapper<Object> builderWrapper = ObjectWrapper.invokeStaticMethod(daggerComponent, "builder");
        for (Object module : modules) {
            builderWrapper = builderWrapper.invokeBuilderSetter(module.getClass(), moduleOverrider.override(module));
        }
        return builderWrapper.getValue();
    }

    private Object initComponentDependencies(Object componentBuilder, ModuleOverrider moduleOverrider) {
        try {
            for (Map.Entry<Class, List<Object>> entry : dependencies.entrySet()) {
                Object componentDependencyBuilder = initComponent(entry.getKey(), entry.getValue(), moduleOverrider);
                Object componentDependency = ReflectUtils.buildComponent(componentDependencyBuilder);
                Method setMethod = ReflectUtils.getComponentSetterMethod(componentBuilder, componentDependency);
                componentBuilder = setMethod.invoke(componentBuilder, componentDependency);
            }
            return componentBuilder;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public interface ComponentSetter<C> {
        void setComponent(C component);
    }
}
