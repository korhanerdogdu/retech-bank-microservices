package com.banking.apigateway.config;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.Optional;

/**
 * Tells downstream services who is calling. Any X-User-Id sent by a client is
 * removed; the header is set only from the "sub" claim of a validated JWT.
 */
@Component
public class UserIdRelayFilter implements GlobalFilter, Ordered {

    public static final String USER_ID_HEADER = "X-User-Id";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        return exchange.getPrincipal()
                .filter(JwtAuthenticationToken.class::isInstance)
                .map(principal -> Optional.of(((JwtAuthenticationToken) principal).getToken().getSubject()))
                .defaultIfEmpty(Optional.empty())
                .flatMap(userId -> chain.filter(exchange.mutate()
                        .request(request -> request.headers(headers -> {
                            headers.remove(USER_ID_HEADER);
                            userId.ifPresent(id -> headers.set(USER_ID_HEADER, id));
                        }))
                        .build()));
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
