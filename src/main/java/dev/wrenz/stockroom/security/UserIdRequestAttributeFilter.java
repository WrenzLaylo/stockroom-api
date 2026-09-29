package dev.wrenz.stockroom.security;

import dev.wrenz.stockroom.common.RequestLoggingFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/** Runs inside the security chain, after the token is checked, and records the user ID for the request log. Not a @Component, so it isn't registered twice. */
class UserIdRequestAttributeFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof JwtAuthenticationToken jwt) {
            request.setAttribute(RequestLoggingFilter.USER_ID_ATTRIBUTE, jwt.getToken().getSubject());
        }
        chain.doFilter(request, response);
    }
}
