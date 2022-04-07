package com.github.lianjiatech.retrofit.spring.boot.core;

import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import com.github.lianjiatech.retrofit.spring.boot.degrade.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.core.env.Environment;
import org.springframework.util.Assert;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import com.github.lianjiatech.retrofit.spring.boot.annotation.Intercept;
import com.github.lianjiatech.retrofit.spring.boot.annotation.InterceptMark;
import com.github.lianjiatech.retrofit.spring.boot.annotation.Intercepts;
import com.github.lianjiatech.retrofit.spring.boot.annotation.OkHttpClientBuilder;
import com.github.lianjiatech.retrofit.spring.boot.annotation.RetrofitClient;
import com.github.lianjiatech.retrofit.spring.boot.config.DegradeProperty;
import com.github.lianjiatech.retrofit.spring.boot.config.LogProperty;
import com.github.lianjiatech.retrofit.spring.boot.config.RetrofitConfigBean;
import com.github.lianjiatech.retrofit.spring.boot.config.RetrofitProperties;
import com.github.lianjiatech.retrofit.spring.boot.interceptor.BaseLoggingInterceptor;
import com.github.lianjiatech.retrofit.spring.boot.interceptor.BasePathMatchInterceptor;
import com.github.lianjiatech.retrofit.spring.boot.interceptor.ErrorDecoderInterceptor;
import com.github.lianjiatech.retrofit.spring.boot.interceptor.GlobalInterceptor;
import com.github.lianjiatech.retrofit.spring.boot.interceptor.LogLevel;
import com.github.lianjiatech.retrofit.spring.boot.interceptor.LogStrategy;
import com.github.lianjiatech.retrofit.spring.boot.interceptor.NetworkInterceptor;
import com.github.lianjiatech.retrofit.spring.boot.interceptor.ServiceInstanceChooserInterceptor;
import com.github.lianjiatech.retrofit.spring.boot.util.ApplicationContextUtils;
import com.github.lianjiatech.retrofit.spring.boot.util.BeanExtendUtils;
import com.github.lianjiatech.retrofit.spring.boot.util.RetrofitUtils;

import okhttp3.ConnectionPool;
import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import retrofit2.CallAdapter;
import retrofit2.Converter;
import retrofit2.Retrofit;

/**
 * @author 陈添明
 */
public class RetrofitFactoryBean<T> implements FactoryBean<T>, EnvironmentAware, ApplicationContextAware {

    private final static Logger logger = LoggerFactory.getLogger(RetrofitFactoryBean.class);

    private static final Map<Class<? extends CallAdapter.Factory>, CallAdapter.Factory> CALL_ADAPTER_FACTORIES_CACHE =
            new HashMap<>(4);

    private final Class<T> retrofitInterface;

    private Environment environment;

    private RetrofitProperties retrofitProperties;

    private RetrofitConfigBean retrofitConfigBean;

    private ApplicationContext applicationContext;

    private final RetrofitClient retrofitClient;

    private static final Map<Class<? extends Converter.Factory>, Converter.Factory> CONVERTER_FACTORIES_CACHE =
            new HashMap<>(4);

    public RetrofitFactoryBean(Class<T> retrofitInterface) {
        this.retrofitInterface = retrofitInterface;
        retrofitClient = retrofitInterface.getAnnotation(RetrofitClient.class);
    }

    @Override
    @SuppressWarnings("unchecked")
    public T getObject() throws Exception {
        checkRetrofitInterface(retrofitInterface);
        Retrofit retrofit = getRetrofit(retrofitInterface);
        // source
        T source = retrofit.create(retrofitInterface);

        RetrofitProperties retrofitProperties = retrofitConfigBean.getRetrofitProperties();
        Class<?> fallbackClass = retrofitClient.fallback();
        Object fallback = null;
        if (!void.class.isAssignableFrom(fallbackClass)) {
            fallback = ApplicationContextUtils.getBean(applicationContext, fallbackClass);
        }
        Class<?> fallbackFactoryClass = retrofitClient.fallbackFactory();
        FallbackFactory<?> fallbackFactory = null;
        if (!void.class.isAssignableFrom(fallbackFactoryClass)) {
            fallbackFactory =
                    (FallbackFactory)ApplicationContextUtils.getBean(applicationContext, fallbackFactoryClass);
        }
        loadDegradeRules();
        // proxy
        return (T)Proxy.newProxyInstance(retrofitInterface.getClassLoader(),
                new Class<?>[] {retrofitInterface},
                new RetrofitInvocationHandler(source, fallback, fallbackFactory, retrofitProperties)

        );
    }

    /**
     * 加载熔断配置,熔断粒度可控制到方法级别
     */
    private void loadDegradeRules() {
        DegradeProperty degradeProperty = retrofitProperties.getDegrade();
        DegradeRuleRegister degradeRuleRegister = retrofitConfigBean.getDegradeRuleRegister();

        if (!degradeProperty.isEnable()) {
            return;
        }
        Assert.notNull(degradeRuleRegister, "[DegradeRuleRegister] not found bean instance");
        DegradeType degradeType = degradeProperty.getDegradeType();
        switch (degradeType) {
            case SENTINEL: {
                Method[] methods = retrofitInterface.getMethods();
                List<RetrofitDegradeRule> retrofitDegradeRuleList =
                        Arrays.stream(methods).map(this::convertSentinelRule).filter(Objects::nonNull).collect(Collectors.toList());
                degradeRuleRegister.batchRegister(retrofitDegradeRuleList);
                break;
            }
            case RESILIENCE4J: {
                break;
            }
            default: {
                throw new IllegalArgumentException("Not currently supported! degradeType=" + degradeType);
            }

        }


    }

    /**
     * TODO 可以优化 和{@link com.github.lianjiatech.retrofit.spring.boot.degrade.SentinelDegradeRuleRegister#convert(com.github.lianjiatech.retrofit.spring.boot.degrade.RetrofitDegradeRule)}放到一起
     * Sentinel 规则转换器
     * @param method method
     * @return RetrofitDegradeRule
     */
    private RetrofitDegradeRule convertSentinelRule(Method method) {
        if (method.isDefault()) {
            return null;
        }
        int modifiers = method.getModifiers();
        if (Modifier.isStatic(modifiers)) {
            return null;
        }
        // 获取熔断配置
        Degrade degrade;
        if (method.isAnnotationPresent(Degrade.class)) {
            degrade = method.getAnnotation(Degrade.class);
        } else {
            degrade = retrofitInterface.getAnnotation(Degrade.class);
        }

        if (degrade == null) {
            return null;
        }

        DegradeStrategy degradeStrategy = degrade.degradeStrategy();
        BaseResourceNameParser resourceNameParser = retrofitConfigBean.getResourceNameParser();
        String resourceName = resourceNameParser.parseResourceName(method, environment);

        RetrofitDegradeRule degradeRule = new RetrofitDegradeRule();
        degradeRule.setCount(degrade.count());
        degradeRule.setDegradeStrategy(degradeStrategy);
        degradeRule.setTimeWindow(degrade.timeWindow());
        degradeRule.setResourceName(resourceName);
        return degradeRule;
    }

    /**
     * RetrofitInterface检查
     *
     * @param retrofitInterface .
     */
    private void checkRetrofitInterface(Class<T> retrofitInterface) {
        // check class type
        Assert.isTrue(retrofitInterface.isInterface(), "@RetrofitClient can only be marked on the interface type!");
        Method[] methods = retrofitInterface.getMethods();

        Assert.isTrue(StringUtils.hasText(retrofitClient.baseUrl()) || StringUtils.hasText(retrofitClient.serviceId()),
                "@RetrofitClient's baseUrl and serviceId must be configured with one！");

        for (Method method : methods) {
            Class<?> returnType = method.getReturnType();
            if (method.isAnnotationPresent(OkHttpClientBuilder.class)) {
                Assert.isTrue(returnType.equals(OkHttpClient.Builder.class),
                        "For methods annotated by @OkHttpClientBuilder, the return value must be OkHttpClient.Builder！");
                Assert.isTrue(Modifier.isStatic(method.getModifiers()),
                        "only static method can annotated by @OkHttpClientBuilder!");
                continue;
            }

            Assert.isTrue(!void.class.isAssignableFrom(returnType),
                    "The void keyword is not supported as the return type, please use java.lang.Void！ method="
                            + method);
            if (retrofitProperties.isDisableVoidReturnType()) {
                Assert.isTrue(!Void.class.isAssignableFrom(returnType),
                        "Configured to disable Void as the return value, please specify another return type!method="
                                + method);
            }
        }

        Class<?> fallbackClass = retrofitClient.fallback();
        if (!void.class.isAssignableFrom(fallbackClass)) {
            Assert.isTrue(retrofitInterface.isAssignableFrom(fallbackClass),
                    "The fallback type must implement the current interface！the fallback type is " + fallbackClass);
            Object fallback = ApplicationContextUtils.getBean(applicationContext, fallbackClass);
            Assert.notNull(fallback, "fallback  must be a valid spring bean! the fallback class is " + fallbackClass);
        }

        Class<?> fallbackFactoryClass = retrofitClient.fallbackFactory();
        if (!void.class.isAssignableFrom(fallbackFactoryClass)) {
            Assert.isTrue(FallbackFactory.class.isAssignableFrom(fallbackFactoryClass),
                    "The fallback factory type must implement FallbackFactory！the fallback factory is "
                            + fallbackFactoryClass);
            Object fallbackFactory = ApplicationContextUtils.getBean(applicationContext, fallbackFactoryClass);
            Assert.notNull(fallbackFactory,
                    "fallback factory  must be a valid spring bean! the fallback factory class is "
                            + fallbackFactoryClass);
        }
    }

    @Override
    public Class<T> getObjectType() {
        return this.retrofitInterface;
    }

    @Override
    public boolean isSingleton() {
        return true;
    }

    /**
     * Get okhttp3 connection pool
     *
     * @param retrofitClientInterfaceClass retrofitClientInterfaceClass
     * @return okhttp3 connection pool
     */
    private synchronized okhttp3.ConnectionPool getConnectionPool(Class<?> retrofitClientInterfaceClass) {
        RetrofitClient retrofitClient = retrofitClientInterfaceClass.getAnnotation(RetrofitClient.class);
        String poolName = retrofitClient.poolName();
        Map<String, ConnectionPool> poolRegistry = retrofitConfigBean.getPoolRegistry();
        Assert.notNull(poolRegistry, "poolRegistry does not exist! Please set retrofitConfigBean.poolRegistry!");
        ConnectionPool connectionPool = poolRegistry.get(poolName);
        Assert.notNull(connectionPool,
                "The connection pool corresponding to the current poolName does not exist! poolName = " + poolName);
        return connectionPool;
    }

    /**
     * Get OkHttpClient instance, one interface corresponds to one OkHttpClient
     *
     * @param retrofitClientInterfaceClass retrofitClientInterfaceClass
     * @return OkHttpClient instance
     */
    private synchronized OkHttpClient getOkHttpClient(Class<?> retrofitClientInterfaceClass)
            throws IllegalAccessException, InstantiationException, NoSuchMethodException, InvocationTargetException {
        RetrofitClient retrofitClient = retrofitClientInterfaceClass.getAnnotation(RetrofitClient.class);
        Method method = findOkHttpClientBuilderMethod(retrofitClientInterfaceClass);
        OkHttpClient.Builder okHttpClientBuilder;
        if (method != null) {
            okHttpClientBuilder = (OkHttpClient.Builder)method.invoke(null);
        } else {
            okhttp3.ConnectionPool connectionPool = getConnectionPool(retrofitClientInterfaceClass);

            final int connectTimeoutMs = retrofitClient.connectTimeoutMs() == -1
                    ? retrofitProperties.getGlobalConnectTimeoutMs() : retrofitClient.connectTimeoutMs();
            final int readTimeoutMs = retrofitClient.readTimeoutMs() == -1 ? retrofitProperties.getGlobalReadTimeoutMs()
                    : retrofitClient.readTimeoutMs();
            final int writeTimeoutMs = retrofitClient.writeTimeoutMs() == -1
                    ? retrofitProperties.getGlobalWriteTimeoutMs() : retrofitClient.writeTimeoutMs();
            final int callTimeoutMs = retrofitClient.callTimeoutMs() == -1 ? retrofitProperties.getGlobalCallTimeoutMs()
                    : retrofitClient.callTimeoutMs();

            // Construct an OkHttpClient object
            okHttpClientBuilder = new OkHttpClient.Builder()
                    .connectTimeout(connectTimeoutMs, TimeUnit.MILLISECONDS)
                    .readTimeout(readTimeoutMs, TimeUnit.MILLISECONDS)
                    .writeTimeout(writeTimeoutMs, TimeUnit.MILLISECONDS)
                    .callTimeout(callTimeoutMs, TimeUnit.MILLISECONDS)
                    .retryOnConnectionFailure(retrofitClient.retryOnConnectionFailure())
                    .followRedirects(retrofitClient.followRedirects())
                    .followSslRedirects(retrofitClient.followSslRedirects())
                    .pingInterval(retrofitClient.pingIntervalMs(), TimeUnit.MILLISECONDS)
                    .connectionPool(connectionPool);
        }

        // add DegradeInterceptor
        // TODO 这里稍微有点问题，开启熔断则所有实例都会加熔断拦截器，但是拦截器升不生效则取决于是否配置了@Degrade注解，可不可以把这里做成有一个全局默认配置，有@Degrade则走单独配置？
        DegradeProperty degradeProperty = retrofitProperties.getDegrade();
        if (degradeProperty.isEnable()) {
            DegradeType degradeType = degradeProperty.getDegradeType();
            switch (degradeType) {
                case SENTINEL: {
                    try {
                        Class.forName("com.alibaba.csp.sentinel.SphU");
                        DegradeInterceptor degradeInterceptor = new DegradeInterceptor();
                        degradeInterceptor.setEnvironment(environment);
                        degradeInterceptor.setResourceNameParser(retrofitConfigBean.getResourceNameParser());
                        degradeInterceptor.setDegradeRuleRegister(retrofitConfigBean.getDegradeRuleRegister());
                        okHttpClientBuilder.addInterceptor(degradeInterceptor);
                    } catch (ClassNotFoundException e) {
                        logger.warn("com.alibaba.csp.sentinel not found! No SentinelDegradeInterceptor is set.");
                    }
                    break;
                }
                default: {
                    throw new IllegalArgumentException("Not currently supported! degradeType=" + degradeType);
                }
            }
        }

        // add ServiceInstanceChooserInterceptor
        if (StringUtils.hasText(retrofitClient.serviceId())) {
            ServiceInstanceChooserInterceptor serviceInstanceChooserInterceptor =
                    retrofitConfigBean.getServiceInstanceChooserInterceptor();
            if (serviceInstanceChooserInterceptor != null) {
                okHttpClientBuilder.addInterceptor(serviceInstanceChooserInterceptor);
            }
        }

        // add ErrorDecoderInterceptor
        Class<? extends ErrorDecoder> errorDecoderClass = retrofitClient.errorDecoder();
        ErrorDecoder decoder = ApplicationContextUtils.getBean(applicationContext, errorDecoderClass);
        if (decoder == null) {
            decoder = errorDecoderClass.newInstance();
        }
        ErrorDecoderInterceptor decoderInterceptor = ErrorDecoderInterceptor.create(decoder);
        okHttpClientBuilder.addInterceptor(decoderInterceptor);

        // Add the interceptor defined by the annotation on the interface
        List<Interceptor> interceptors = new ArrayList<>(findInterceptorByAnnotation(retrofitClientInterfaceClass));
        // add global interceptor
        List<GlobalInterceptor> globalInterceptors = retrofitConfigBean.getGlobalInterceptors();
        if (!CollectionUtils.isEmpty(globalInterceptors)) {
            interceptors.addAll(globalInterceptors);
        }
        interceptors.forEach(okHttpClientBuilder::addInterceptor);

        // add retry interceptor
        Interceptor retryInterceptor = retrofitConfigBean.getRetryInterceptor();
        okHttpClientBuilder.addInterceptor(retryInterceptor);

        // add log printing interceptor
        LogProperty logProperty = retrofitProperties.getLog();
        if (logProperty.isEnable() && retrofitClient.enableLog()) {
            Class<? extends BaseLoggingInterceptor> loggingInterceptorClass = logProperty.getLoggingInterceptor();
            Constructor<? extends BaseLoggingInterceptor> constructor =
                    loggingInterceptorClass.getConstructor(LogLevel.class, LogStrategy.class);
            LogLevel logLevel = retrofitClient.logLevel();
            LogStrategy logStrategy = retrofitClient.logStrategy();
            if (logLevel.equals(LogLevel.NULL)) {
                logLevel = logProperty.getGlobalLogLevel();
            }
            if (logStrategy.equals(LogStrategy.NULL)) {
                logStrategy = logProperty.getGlobalLogStrategy();
            }

            Assert.isTrue(!logLevel.equals(LogLevel.NULL), "LogLevel cannot all be configured as LogLevel.NULL!");
            Assert.isTrue(!logStrategy.equals(LogStrategy.NULL),
                    "logStrategy cannot all be configured as LogStrategy.NULL!");

            BaseLoggingInterceptor loggingInterceptor = constructor.newInstance(logLevel, logStrategy);
            okHttpClientBuilder.addNetworkInterceptor(loggingInterceptor);
        }

        List<NetworkInterceptor> networkInterceptors = retrofitConfigBean.getNetworkInterceptors();
        if (!CollectionUtils.isEmpty(networkInterceptors)) {
            for (NetworkInterceptor networkInterceptor : networkInterceptors) {
                okHttpClientBuilder.addNetworkInterceptor(networkInterceptor);
            }
        }

        return okHttpClientBuilder.build();
    }

    private Method findOkHttpClientBuilderMethod(Class<?> retrofitClientInterfaceClass) {
        Method[] methods = retrofitClientInterfaceClass.getMethods();
        for (Method method : methods) {
            if (Modifier.isStatic(method.getModifiers())
                    && method.isAnnotationPresent(OkHttpClientBuilder.class)
                    && method.getReturnType().equals(OkHttpClient.Builder.class)) {
                return method;
            }
        }
        return null;
    }

    /**
     * 获取retrofitClient接口类上定义的拦截器集合
     * Get the interceptor set defined on the retrofitClient interface class
     *
     * @param retrofitClientInterfaceClass retrofitClientInterfaceClass
     * @return the interceptor list
     */
    @SuppressWarnings("unchecked")
    private List<Interceptor> findInterceptorByAnnotation(Class<?> retrofitClientInterfaceClass)
            throws InstantiationException, IllegalAccessException {
        Annotation[] classAnnotations = retrofitClientInterfaceClass.getAnnotations();
        List<Interceptor> interceptors = new ArrayList<>();
        // 找出被@InterceptMark标记的注解。Find the annotation marked by @InterceptMark
        List<Annotation> interceptAnnotations = new ArrayList<>();
        for (Annotation classAnnotation : classAnnotations) {
            Class<? extends Annotation> annotationType = classAnnotation.annotationType();
            if (annotationType.isAnnotationPresent(InterceptMark.class)) {
                interceptAnnotations.add(classAnnotation);
            }
            if (classAnnotation instanceof Intercepts) {
                Intercept[] value = ((Intercepts)classAnnotation).value();
                for (Intercept intercept : value) {
                    interceptAnnotations.add(intercept);
                }
            }
        }
        for (Annotation interceptAnnotation : interceptAnnotations) {
            // 获取注解属性数据。Get annotation attribute data
            Map<String, Object> annotationAttributes = AnnotationUtils.getAnnotationAttributes(interceptAnnotation);
            Object handler = annotationAttributes.get("handler");
            Assert.notNull(handler,
                    "@InterceptMark annotations must be configured: Class<? extends BasePathMatchInterceptor> handler()");
            Assert.notNull(annotationAttributes.get("include"),
                    "@InterceptMark annotations must be configured: String[] include()");
            Assert.notNull(annotationAttributes.get("exclude"),
                    "@InterceptMark annotations must be configured: String[] exclude()");
            Class<? extends BasePathMatchInterceptor> interceptorClass =
                    (Class<? extends BasePathMatchInterceptor>)handler;
            BasePathMatchInterceptor interceptor =
                    ApplicationContextUtils.getTargetInstanceIfNecessary(getInterceptorInstance(interceptorClass));
            Map<String, Object> annotationResolveAttributes = new HashMap<>(8);
            // 占位符属性替换。Placeholder attribute replacement
            annotationAttributes.forEach((key, value) -> {
                if (value instanceof String) {
                    String newValue = environment.resolvePlaceholders((String)value);
                    annotationResolveAttributes.put(key, newValue);
                } else {
                    annotationResolveAttributes.put(key, value);
                }
            });
            // 动态设置属性值。Set property value dynamically
            BeanExtendUtils.populate(interceptor, annotationResolveAttributes);
            interceptors.add(interceptor);
        }
        return interceptors;
    }

    /**
     * 获取路径拦截器实例，优先从spring容器中取。如果spring容器中不存在，则无参构造器实例化一个。
     * Obtain the path interceptor instance, first from the spring container. If it does not exist in the spring container, the no-argument constructor will instantiate one.
     *
     * @param interceptorClass A subclass of @{@link BasePathMatchInterceptor}
     * @return @{@link BasePathMatchInterceptor} instance
     */
    private BasePathMatchInterceptor getInterceptorInstance(Class<? extends BasePathMatchInterceptor> interceptorClass)
            throws IllegalAccessException, InstantiationException {
        // spring bean
        try {
            return applicationContext.getBean(interceptorClass);
        } catch (BeansException e) {
            // spring容器获取失败，反射创建
            return interceptorClass.newInstance();
        }
    }

    /**
     * 获取Retrofit实例，一个retrofitClient接口对应一个Retrofit实例
     * Obtain a Retrofit instance, a retrofitClient interface corresponds to a Retrofit instance
     *
     * @param retrofitClientInterfaceClass retrofitClientInterfaceClass
     * @return Retrofit instance
     */
    private synchronized Retrofit getRetrofit(Class<?> retrofitClientInterfaceClass)
            throws InstantiationException, IllegalAccessException, NoSuchMethodException, InvocationTargetException {
        RetrofitClient retrofitClient = retrofitClientInterfaceClass.getAnnotation(RetrofitClient.class);
        String baseUrl = retrofitClient.baseUrl();

        baseUrl = RetrofitUtils.convertBaseUrl(retrofitClient, baseUrl, environment);

        OkHttpClient client = getOkHttpClient(retrofitClientInterfaceClass);
        Retrofit.Builder retrofitBuilder = new Retrofit.Builder()
                .baseUrl(baseUrl)
                .validateEagerly(retrofitClient.validateEagerly())
                .client(client);

        // 添加CallAdapter.Factory
        Class<? extends CallAdapter.Factory>[] callAdapterFactoryClasses = retrofitClient.callAdapterFactories();
        Class<? extends CallAdapter.Factory>[] globalCallAdapterFactoryClasses =
                retrofitConfigBean.getGlobalCallAdapterFactoryClasses();
        List<CallAdapter.Factory> callAdapterFactories =
                getCallAdapterFactories(callAdapterFactoryClasses, globalCallAdapterFactoryClasses);
        if (!CollectionUtils.isEmpty(callAdapterFactories)) {
            callAdapterFactories.forEach(retrofitBuilder::addCallAdapterFactory);
        }
        // 添加Converter.Factory
        Class<? extends Converter.Factory>[] converterFactoryClasses = retrofitClient.converterFactories();
        Class<? extends Converter.Factory>[] globalConverterFactoryClasses =
                retrofitConfigBean.getGlobalConverterFactoryClasses();

        List<Converter.Factory> converterFactories =
                getConverterFactories(converterFactoryClasses, globalConverterFactoryClasses);
        if (!CollectionUtils.isEmpty(converterFactories)) {
            converterFactories.forEach(retrofitBuilder::addConverterFactory);
        }
        return retrofitBuilder.build();
    }

    private List<CallAdapter.Factory> getCallAdapterFactories(
            Class<? extends CallAdapter.Factory>[] callAdapterFactoryClasses,
            Class<? extends CallAdapter.Factory>[] globalCallAdapterFactoryClasses)
            throws IllegalAccessException, InstantiationException {
        List<Class<? extends CallAdapter.Factory>> combineCallAdapterFactoryClasses = new ArrayList<>();

        if (callAdapterFactoryClasses != null && callAdapterFactoryClasses.length != 0) {
            combineCallAdapterFactoryClasses.addAll(Arrays.asList(callAdapterFactoryClasses));
        }

        if (globalCallAdapterFactoryClasses != null && globalCallAdapterFactoryClasses.length != 0) {
            combineCallAdapterFactoryClasses.addAll(Arrays.asList(globalCallAdapterFactoryClasses));
        }

        if (combineCallAdapterFactoryClasses.isEmpty()) {
            return Collections.emptyList();
        }

        List<CallAdapter.Factory> callAdapterFactories = new ArrayList<>();

        for (Class<? extends CallAdapter.Factory> callAdapterFactoryClass : combineCallAdapterFactoryClasses) {
            CallAdapter.Factory callAdapterFactory = CALL_ADAPTER_FACTORIES_CACHE.get(callAdapterFactoryClass);
            if (callAdapterFactory == null) {
                callAdapterFactory = ApplicationContextUtils.getBean(applicationContext, callAdapterFactoryClass);
                if (callAdapterFactory == null) {
                    callAdapterFactory = callAdapterFactoryClass.newInstance();
                }
                CALL_ADAPTER_FACTORIES_CACHE.put(callAdapterFactoryClass, callAdapterFactory);
            }
            callAdapterFactories.add(callAdapterFactory);
        }
        return callAdapterFactories;
    }

    private List<Converter.Factory> getConverterFactories(Class<? extends Converter.Factory>[] converterFactoryClasses,
            Class<? extends Converter.Factory>[] globalConverterFactoryClasses)
            throws IllegalAccessException, InstantiationException {
        List<Class<? extends Converter.Factory>> combineConverterFactoryClasses = new ArrayList<>();

        if (converterFactoryClasses != null && converterFactoryClasses.length != 0) {
            combineConverterFactoryClasses.addAll(Arrays.asList(converterFactoryClasses));
        }

        if (globalConverterFactoryClasses != null && globalConverterFactoryClasses.length != 0) {
            combineConverterFactoryClasses.addAll(Arrays.asList(globalConverterFactoryClasses));
        }

        if (combineConverterFactoryClasses.isEmpty()) {
            return Collections.emptyList();
        }

        List<Converter.Factory> converterFactories = new ArrayList<>();

        for (Class<? extends Converter.Factory> converterFactoryClass : combineConverterFactoryClasses) {
            Converter.Factory converterFactory = CONVERTER_FACTORIES_CACHE.get(converterFactoryClass);
            if (converterFactory == null) {
                converterFactory = ApplicationContextUtils.getBean(applicationContext, converterFactoryClass);
                if (converterFactory == null) {
                    converterFactory = converterFactoryClass.newInstance();
                }
                CONVERTER_FACTORIES_CACHE.put(converterFactoryClass, converterFactory);
            }
            converterFactories.add(converterFactory);
        }
        return converterFactories;
    }

    @Override
    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        this.applicationContext = applicationContext;
        this.retrofitConfigBean = applicationContext.getBean(RetrofitConfigBean.class);
        this.retrofitProperties = retrofitConfigBean.getRetrofitProperties();
    }
}
