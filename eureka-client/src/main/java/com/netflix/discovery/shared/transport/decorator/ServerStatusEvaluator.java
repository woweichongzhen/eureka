/*
 * Copyright 2015 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.netflix.discovery.shared.transport.decorator;

import com.netflix.discovery.shared.transport.decorator.EurekaHttpClientDecorator.RequestType;

/**
 * HTTP状态代码评估器，可用于决定立即在另一台服务器上重试请求还是坚持当前请求是否有意义。
 * 注册请求对于尽快完成至关重要，因此，在服务器出现任何错误之后，请重试另一个请求。
 * 注册表获取/增量获取应坚持在同一台服务器上，以避免增量哈希码不匹配
 * <p>
 * HTTP status code evaluator, that can be used to make a decision whether it makes sense to
 * immediately retry a request on another server or stick to the current one.
 * Registration requests are critical to complete as soon as possible, so any server error should be followed
 * by retry on another one. Registry fetch/delta fetch should stick to the same server, to avoid delta hash code
 * mismatches. See https://github.com/Netflix/eureka/issues/628.
 *
 * @author Tomasz Bak
 */
public interface ServerStatusEvaluator {
    boolean accept(int statusCode, RequestType requestType);
}
