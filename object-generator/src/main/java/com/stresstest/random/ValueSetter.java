package com.stresstest.random;

import java.lang.reflect.Field;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.TreeSet;

import com.google.common.base.Function;
import com.google.common.base.Predicate;
import com.google.common.collect.Collections2;

abstract public class ValueSetter<T> {

    abstract public void setValue(Object target);

    abstract protected Class<?> getAffectedClass();

    final private static class SimpleValueSetter<T> extends ValueSetter<T> {

        final private Field field;
        final private Method method;
        final private ValueGenerator<T> valueGenerator;

        private SimpleValueSetter(Field field, Method method, ValueGenerator<T> valueGenerator) {
            this.field = field;
            this.method = method;
            this.valueGenerator = valueGenerator;
        }

        @Override
        public void setValue(Object target) {
            Object valueToSet = valueGenerator.generate();
            // Step 1. Setting method preferably to field as a regular expression
            try {
                if (method != null) {
                    method.invoke(target, valueToSet);
                } else {
                    field.set(target, valueToSet);
                }
            } catch (Exception methodSetException) {
                // Step 3. Setting method, changing it access level prior to setting
                try {
                    if (method != null) {
                        method.setAccessible(true);
                        method.invoke(target, valueToSet);
                    } else {
                        field.setAccessible(true);
                        field.set(target, valueToSet);
                    }
                } catch (Exception anotherMethodSetException) {
                }
            }
        }

        @Override
        protected Class<?> getAffectedClass() {
            return field != null ? field.getDeclaringClass() : method.getDeclaringClass();
        }

        @Override
        public String toString() {
            return (field != null ? field.getName() : "-") + " / " + (method != null ? method.getName() : "-");
        }

    }

    final private static class CollectionValueSetter<T> extends ValueSetter<T> {

        final private ValueSetter<T> initialPropertySetter;
        final private Method method;
        final private ValueGenerator<T> valueGenerator;

        private CollectionValueSetter(final ValueSetter<T> initialPropertySetter, final Method method, final ValueGenerator<T> valueGenerator) {
            this.initialPropertySetter = initialPropertySetter;
            this.method = method;
            this.valueGenerator = valueGenerator;
        }

        @Override
        public void setValue(Object target) {
            initialPropertySetter.setValue(target);
            Object valueToSet = valueGenerator.generate();
            // Step 1. Setting method as a regular expression
            try {
                if (method != null)
                    method.invoke(target, valueToSet);
            } catch (Exception methodSetException) {
                try {
                    method.setAccessible(true);
                    method.invoke(target, valueToSet);
                } catch (Exception exception) {
                }
            }
        }

        @Override
        protected Class<?> getAffectedClass() {
            return initialPropertySetter.getAffectedClass();
        }

        @Override
        public String toString() {
            return initialPropertySetter.toString();
        }
    }

    final private static Predicate<Member> FILTER_APPLICABLE_METHODS = new Predicate<Member>() {
        @Override
        public boolean apply(Member input) {
            if ((input.getModifiers() & Modifier.STATIC) != 0)
                return false;
            String name = input.getName().toLowerCase();
            return name.startsWith("set") || name.startsWith("add");
        }
    };

    final private static Function<Member, String> EXTRACT_MEMBER_NAME = new Function<Member, String>() {
        @Override
        public String apply(Member method) {
            final String lowerMethodName = method.getName().toLowerCase();
            return lowerMethodName.startsWith("set") || lowerMethodName.startsWith("add") ? lowerMethodName.substring(3) : lowerMethodName;
        }
    };

    final private static Function<Member, String> EXTRACT_ADD_METHOD_NAME = new Function<Member, String>() {
        @Override
        public String apply(Member method) {
            return EXTRACT_MEMBER_NAME.apply(method) + "s";
        }
    };

    final private static Function<Field, String> EXTRACT_FIELD_NAME = new Function<Field, String>() {
        @Override
        public String apply(Field field) {
            return field.getName().toLowerCase();
        }
    };

    final private static Comparator<ValueSetter<?>> COMPARE_STRING_PRESENTATION = new Comparator<ValueSetter<?>>() {
        @Override
        public int compare(final ValueSetter<?> firstPropertySetter, final ValueSetter<?> secondPropertySetter) {
            return firstPropertySetter.toString().compareTo(secondPropertySetter.toString());
        }
    };

    final private static Comparator<ValueSetter<?>> COMPARE_PRESENTATION_TYPE = new Comparator<ValueSetter<?>>() {
        @Override
        public int compare(final ValueSetter<?> firstPropertySetter, final ValueSetter<?> secondPropertySetter) {
            boolean firstSimpleProperty = firstPropertySetter instanceof SimpleValueSetter;
            boolean secondSimpleProperty = secondPropertySetter instanceof SimpleValueSetter;
            if (firstSimpleProperty && secondSimpleProperty) {
                // Step 1. Check field names
                Field firstField = ((SimpleValueSetter<?>) firstPropertySetter).field;
                Field secondField = ((SimpleValueSetter<?>) secondPropertySetter).field;
                if (firstField != null && secondField != null) {
                    int comparison = secondField.getName().compareTo(firstField.getName());
                    if (comparison != 0)
                        return comparison;
                }
                // Step 2. Check method names
                Method firstMethod = ((SimpleValueSetter<?>) firstPropertySetter).method;
                Method secondMethod = ((SimpleValueSetter<?>) secondPropertySetter).method;
                if (firstMethod != null && secondMethod != null) {
                    int comparison = secondMethod.getName().compareTo(firstMethod.getName());
                    if (comparison != 0)
                        return comparison;
                }
                // Step 2. Check classes
                Class<?> firstClass = ((SimpleValueSetter<?>) firstPropertySetter).getAffectedClass();
                Class<?> secondClass = ((SimpleValueSetter<?>) secondPropertySetter).getAffectedClass();
                if (firstClass != secondClass) {
                    return firstClass.isAssignableFrom(secondClass) ? 1 : -1;
                }
            } else if (!firstSimpleProperty && !secondSimpleProperty) {
                // Comparison of Collections is equivalent to comparison of the types
                return compare(((CollectionValueSetter<?>) firstPropertySetter).initialPropertySetter,
                        ((CollectionValueSetter<?>) secondPropertySetter).initialPropertySetter);
            } else if (firstSimpleProperty) {
                return 1;
            } else if (secondSimpleProperty) {
                return -1;
            }
            return 0;
        }
    };

    private static Field findField(final Class searchClass, final String fieldName) {
        Collection<Field> fieldCandidates = Collections2.filter(Arrays.asList(searchClass.getDeclaredFields()), new Predicate<Field>() {
            @Override
            public boolean apply(Field field) {
                return fieldName.equals(EXTRACT_FIELD_NAME.apply(field));
            }
        });

        return fieldCandidates.isEmpty() ? null : fieldCandidates.iterator().next();
    }

    private static Method findSetMethod(final Class searchClass, final String methodName) {
        Collection<Method> methodCandidates = Collections2.filter(Arrays.asList(searchClass.getMethods()), new Predicate<Method>() {
            @Override
            public boolean apply(Method method) {
                return method.getParameterTypes().length == 1 && method.getName().toLowerCase().startsWith("set")
                        && EXTRACT_MEMBER_NAME.apply(method).equals(methodName);
            }
        });

        return methodCandidates.isEmpty() ? null : methodCandidates.iterator().next();
    }

    private static Method findAddMethod(final Class searchClass, final String methodName) {
        Collection<Method> methodCandidates = Collections2.filter(Arrays.asList(searchClass.getMethods()), new Predicate<Method>() {
            @Override
            public boolean apply(Method method) {
                return method.getParameterTypes().length == 1 && method.getName().toLowerCase().startsWith("add")
                        && EXTRACT_ADD_METHOD_NAME.apply(method).startsWith(methodName);
            }
        });

        return methodCandidates.isEmpty() ? null : methodCandidates.iterator().next();
    }

    public static <T> ValueSetter<T> createFieldSetter(final Field field) {
        return (ValueSetter<T>) createFieldSetter(field, ObjectGenerator.getValueGenerator(field.getType()));
    }

    public static <T> ValueSetter<T> createFieldSetter(final Field field, final ValueGenerator<T> valueGenerator) {
        Method possibleMethods = findSetMethod(field.getDeclaringClass(), EXTRACT_FIELD_NAME.apply(field));
        return create(field, possibleMethods, valueGenerator);
    }

    public static <T> ValueSetter<T> createMethodSetter(final Method method) {
        if (method.getParameterTypes().length != 1)
            return null;
        return (ValueSetter<T>) createMethodSetter(method, ObjectGenerator.getValueGenerator(method.getParameterTypes()[0]));
    }

    public static <T> ValueSetter<T> createMethodSetter(final Method method, final ValueGenerator<T> valueGenerator) {
        Field possibleField = findField(method.getDeclaringClass(), EXTRACT_MEMBER_NAME.apply(method));
        return create(possibleField, method, valueGenerator);
    }

    public static <T> ValueSetter<T> create(final Field field, final Method method, final ValueGenerator<T> valueGenerator) {
        if (field != null && Collection.class.isAssignableFrom(field.getType())) {
            Method addMethod = findAddMethod(field.getDeclaringClass(), EXTRACT_FIELD_NAME.apply(field));
            Method setMethod = findSetMethod(field.getDeclaringClass(), EXTRACT_FIELD_NAME.apply(field));
            ValueSetter<T> collectionValueInitializer = new SimpleValueSetter<T>(field, setMethod, (ValueGenerator<T>) ObjectGenerator.getValueGenerator(field
                    .getType()));
            return new CollectionValueSetter<T>(collectionValueInitializer, addMethod, valueGenerator);
        } else {
            return new SimpleValueSetter<T>(field, method, valueGenerator);
        }
    }

    public static <T> Collection<ValueSetter<?>> extractAvailableProperties(final ClassReflectionAccessWrapper<T> searchClass) {
        // Step 1. Create Collection field setters
        final Collection<ValueSetter<?>> propertySetters = new TreeSet<ValueSetter<?>>(COMPARE_STRING_PRESENTATION);
        propertySetters.addAll(SELECTOR_MANAGER.getApplicableProperties(searchClass));
        for (Field field : searchClass.getFields()) {
            propertySetters.add(createFieldSetter(field));
        }
        // Step 2. Create Collection of method setters
        for (Method method : Collections2.filter(searchClass.getMethods(), FILTER_APPLICABLE_METHODS)) {
            ValueSetter<?> propertySetter = createMethodSetter(method);
            if (propertySetter != null) {
                propertySetters.add(propertySetter);
            }
        }

        final List<ValueSetter<?>> resultSetters = new ArrayList<ValueSetter<?>>(propertySetters);
        Collections.sort(resultSetters, COMPARE_PRESENTATION_TYPE);
        // Step 3. Returning accumulated result
        return resultSetters;
    }

    final private static PropertySetterManager SELECTOR_MANAGER = new PropertySetterManager();

    public static <T> void register(final Class<?> searchClass, final String name, final ValueGenerator<T> valueGenerator) {
        final String possibleName = name.toLowerCase();
        final Field possibleField = findField(searchClass, possibleName);
        final Method possibleMethod = findSetMethod(searchClass, possibleName);
        ValueSetter<T> propertySetter = create(possibleField, possibleMethod, valueGenerator);
        SELECTOR_MANAGER.addSpecificProperties(propertySetter);
    }

    final private static class PropertySetterManager {

        final private Collection<ValueSetter<?>> propertySelectors = new TreeSet<ValueSetter<?>>(COMPARE_PRESENTATION_TYPE);

        public void addSpecificProperties(ValueSetter<?> propertySelector) {
            propertySelectors.remove(propertySelector);
            propertySelectors.add(propertySelector);
        }

        public Collection<ValueSetter<?>> getApplicableProperties(final ClassReflectionAccessWrapper<?> applicableClass) {
            Collection<ValueSetter<?>> applicableSelectors = Collections2.filter(propertySelectors, new Predicate<ValueSetter<?>>() {
                @Override
                public boolean apply(ValueSetter<?> selector) {
                    return applicableClass.canReplace(selector.getAffectedClass());
                }
            });
            List<ValueSetter<?>> sortedSelectors = new ArrayList<ValueSetter<?>>(applicableSelectors);
            Collections.sort(sortedSelectors, COMPARE_PRESENTATION_TYPE);
            return sortedSelectors;
        }

    }

}