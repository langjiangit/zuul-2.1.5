/*
 * Copyright 2018 Netflix, Inc.
 *
 *      Licensed under the Apache License, Version 2.0 (the "License");
 *      you may not use this file except in compliance with the License.
 *      You may obtain a copy of the License at
 *
 *          http://www.apache.org/licenses/LICENSE-2.0
 *
 *      Unless required by applicable law or agreed to in writing, software
 *      distributed under the License is distributed on an "AS IS" BASIS,
 *      WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *      See the License for the specific language governing permissions and
 *      limitations under the License.
 */
package com.netflix.zuul.filters;

import com.netflix.zuul.exception.ZuulFilterConcurrencyExceededException;
import com.netflix.zuul.message.ZuulMessage;
import io.netty.handler.codec.http.HttpContent;
import rx.Observable;

/**
 * BAse interface for ZuulFilters
 *
 * @author Mikey Cohen
 *         Date: 10/27/11
 *         Time: 3:03 PM
 */
public interface ZuulFilter<I extends ZuulMessage, O extends ZuulMessage> extends ShouldFilter<I>
{
    boolean isDisabled();

    String filterName();

    /**
     * filterOrder() must also be defined for a filter. Filters may have the same  filterOrder if precedence is not
     * important for a filter. filterOrders do not need to be sequential.还必须为过滤器定义filterOrder()。如果优先级对筛选器不重要，则筛选器可能具有相同的筛选顺序。过滤命令不需要是连续的。
     *
     * @return the int order of a filter
     */
    int filterOrder();

    /**
     * to classify a filter by type. Standard types in Zuul are "in" for pre-routing filtering,
     * "end" for routing to an origin, "out" for post-routing filters.按类型对筛选器进行分类。Zuul中的标准类型包括用于路由前筛选的“in”、用于路由到源的“end”、用于路由后筛选的“out”。
     *
     * @return FilterType
     */
    FilterType filterType();

    /**
     * Whether this filter's shouldFilter() method should be checked, and apply() called, even
     * if SessionContext.stopFilterProcessing has been set.是否应该检查这个过滤器的shouldFilter()方法，并调用apply()，即使是SessionContext。已经设置了stopFilterProcessing。
     *
     * @return boolean
     */
    boolean overrideStopFilterProcessing();

    /**
     * Called by zuul filter runner before sending request through this filter. The filter can throw
     * ZuulFilterConcurrencyExceededException if it has reached its concurrent requests limit and does
     * not wish to process the request. Generally only useful for async filters.在通过此筛选器发送请求之前，由zuul筛选器运行程序调用。如果过滤器已经达到了并发请求的限制，并且不希望处理请求，那么它可以抛出zuulfilterconcurrencydedexception。一般只对异步过滤器有用。
     */
    void incrementConcurrency() throws ZuulFilterConcurrencyExceededException;

    /**
     * if shouldFilter() is true, this method will be invoked. this method is the core method of a ZuulFilter如果shouldFilter()为真，则调用此方法。该方法是ZuulFilter的核心方法
     */
    Observable<O> applyAsync(I input);

    /**
     * Called by zuul filter after request is processed by this filter.在请求被这个过滤器处理之后，由zuul过滤器调用。
     *
     */
    void decrementConcurrency();

    FilterSyncType getSyncType();

    /**
     * Choose a default message to use if the applyAsync() method throws an exception.如果applyAsync()方法抛出异常，请选择要使用的默认消息。
     *
     * @return ZuulMessage
     */
    O getDefaultOutput(I input);

    /**
     * Filter indicates it needs to read and buffer whole body before it can operate on the messages by returning true.
     * The decision can be made at runtime, looking at the request type. For example if the incoming message is a MSL
     * message MSL decryption filter can return true here to buffer whole MSL message before it tries to decrypt it.
     * @return true if this filter needs to read whole body before it can run, false otherwiseFilter表示它需要读取和缓冲整个主体，然后才能通过返回true对消息进行操作。可以在运行时根据请求类型做出决策。例如，如果传入的消息是MSL消息，MSL解密过滤器可以在这里返回true来缓冲整个MSL消息，然后再尝试解密它。
     */
    boolean needsBodyBuffered(I input);

    /**
     * Optionally transform HTTP content chunk received可选地转换接收到的HTTP内容块
     * @param chunk
     * @return
     */
    HttpContent processContentChunk(ZuulMessage zuulMessage, HttpContent chunk);
}
