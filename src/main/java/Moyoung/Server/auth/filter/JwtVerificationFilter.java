package Moyoung.Server.auth.filter;

import Moyoung.Server.auth.jwt.TokenService;
import Moyoung.Server.auth.utils.JwtUtils;
import Moyoung.Server.member.entity.Member;
import Moyoung.Server.member.service.MemberService;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.security.SignatureException;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Map;
import java.util.Optional;

@RequiredArgsConstructor
public class JwtVerificationFilter extends OncePerRequestFilter {
    private final JwtUtils jwtUtils;
    private final TokenService tokenService;
    private final MemberService memberService;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain) throws IOException, ServletException {
        try {
            Map<String, Object> claims = jwtUtils.getJwsClaimsFromRequest(request);
            setAuthenticationToContext(claims);
        } catch (SignatureException se) {
            request.setAttribute("exception", se);
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "유효하지 않은 토큰입니다.");
            return;
        } catch (ExpiredJwtException ee) {
            request.setAttribute("exception", ee);
            handleTokenExpiration(request, response);
            return;
        } catch (Exception e) {
            request.setAttribute("exception", e);
            response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "서버 오류가 발생했습니다.");
            return;
        }

        filterChain.doFilter(request, response);
    }

    private void handleTokenExpiration(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String refreshTokenHeader = request.getHeader("Refresh");
        if (refreshTokenHeader != null && refreshTokenHeader.startsWith("Bearer ")) {
            String refreshToken = refreshTokenHeader.substring(7);
            try {
                Optional<Member> optionalMember = memberService.findVerifiedMemberById(jwtUtils.getUserIdFromToken(refreshToken));
                if (optionalMember.isPresent()) {
                    Member member = optionalMember.get();
                    String newAccessToken = tokenService.delegateAccessToken(member);

                    Date accessTokenExpiration = tokenService.getAccessTokenExpiration();

                    SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
                    String accessTokenExpirationFormatted = dateFormat.format(accessTokenExpiration);

                    response.setHeader("Authorization", newAccessToken);
                    response.setHeader("AccessTokenExpiration", accessTokenExpirationFormatted);

                    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                    response.getWriter().write("액세스 토큰이 갱신되었습니다");
                } else {
                    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                    response.getWriter().write("유효하지 않은 회원 ID");
                }
            } catch (Exception e) {
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                response.getWriter().write("리프레시 토큰이 만료되었습니다.");
            }
        } else {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.getWriter().write("만료된 토큰입니다. 리프레시 토큰이 누락되었습니다.");

        }
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) throws ServletException {
        String authorization = request.getHeader("Authorization");

        return authorization == null || !authorization.startsWith("Bearer");
    }

    private void setAuthenticationToContext(Map<String, Object> claims) {
        String userId = (String) claims.get("id");
        Authentication authentication = new UsernamePasswordAuthenticationToken(userId, null);
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }
}
