package com.alttalttal.mini_project.jwt;

import com.alttalttal.mini_project.entity.UserRoleEnum;
import com.alttalttal.mini_project.repository.UserRepository;
import com.alttalttal.mini_project.service.RedisService;
import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.security.Key;
import java.util.Base64;
import java.util.Date;

@Component
public class JwtUtil {
    private final UserRepository userRepository;
    private final RedisService redisService;
    public JwtUtil(RedisService redisService, UserRepository userRepository){
        this.redisService = redisService;
        this.userRepository = userRepository;
    }
    // Header의 KEY 값
    public static final String ACCESS_HEADER = "Access";
    public static final String REFRESH_HEADER = "Refresh";

    // 사용자 권한 값의 KEY
    public static final String AUTHORIZATION_KEY = "auth";

    // Token 식별자
    public static final String BEARER_PREFIX = "Bearer ";

    private final SignatureAlgorithm signatureAlgorithm = SignatureAlgorithm.HS256;

    @Value("${jwt.secret.key}") // application.properties의 secretKey 가져옴, Base64 Encode한 값 넣음.
    private String secretKey;

    private Key key;

    // 토큰 유효시간
    public final long ACCESS_TOKEN_TIME = 60 * 30 * 1000L;
    public final long REFRESH_TOKEN_TIME = 60 * 60 * 24 * 30 * 1000L;

//    public final long ACCESS_TOKEN_TIME = 60 * 10 * 100L; // 1분
//    public static final long REFRESH_TOKEN_TIME = 60 * 30 * 100L; // 3분

    // log 설정
    public static final Logger logger = LoggerFactory.getLogger("JWT 관련 로그");

    @PostConstruct
    public void init() {
        byte[] bytes = Base64.getDecoder().decode(secretKey); // Base64로 Encode되어있는 secretKey를 Decode하여 사용
        key = Keys.hmacShaKeyFor(bytes); // 새로운 시크릿키 인스턴스 생성
    }

    //JWT(토큰생성)
    public String createToken(String username, UserRoleEnum role, long tokenValid) {
        Date date = new Date();

        return BEARER_PREFIX +
                Jwts.builder()
                        .setSubject(username) // 사용자 식별값(ID)
                        .claim(AUTHORIZATION_KEY, role) // 사용자 권한
                        .setExpiration(new Date(date.getTime() + tokenValid)) // 생성 시간에 대한 만료시간
                        .setIssuedAt(date) // 발급일
                        .signWith(key, signatureAlgorithm) // 암호화 알고리즘
                        .compact(); //Actually builds the JWT and serializes it to a compact, URL-safe string according to the JWT Compact
    }

    // AccessToken 생성
    public String createAccessToken(String email, UserRoleEnum role) {
        return this.createToken(email, role, ACCESS_TOKEN_TIME);
    }

    // Refresh Token 생성
    public String createRefreshToken(String email, UserRoleEnum role) {
        return this.createToken(email, role, REFRESH_TOKEN_TIME);
    }

    // JWT Cookie에 저장
    public void addJwtToCookie(String token, String tokenValue, HttpServletResponse res){
        try{
            token = URLEncoder.encode(token, "utf-8").replaceAll("\\+", "%20");

            Cookie cookie = new Cookie(tokenValue, token);
            cookie.setPath("/");

            // Response 객체에 Cookie 추가
            res.addCookie(cookie);
        }catch (UnsupportedEncodingException e){
            logger.error(e.getMessage());
        }
    }

    // 받아온 Cookie의 Value인 JWT 토큰 substring
    public String substringToken(String tokenValue){
        if(StringUtils.hasText(tokenValue) && tokenValue.startsWith(BEARER_PREFIX)){
            return tokenValue.substring(7);
        }
        logger.error("Not Found Token");
        throw new NullPointerException("Not Found Token");
    }

    // HttpServletRequest 에서 Cookie Value : JWT 가져오기
    public String getTokenFromRequest(String tokenValue, HttpServletRequest req) {
        Cookie[] cookies = req.getCookies();
        if(cookies != null) {
            for (Cookie cookie : cookies) {
                if (cookie.getName().equals(tokenValue)) {
                    try {
                        return URLDecoder.decode(cookie.getValue(), "UTF-8"); // Encode 되어 넘어간 Value 다시 Decode
                    } catch (UnsupportedEncodingException e) {
                        return null;
                    }
                }
            }
        }
        return null;
    }

    // Token 검증
    public boolean validateToken(String token) {
        try {
            Jwts.parserBuilder().setSigningKey(key).build().parseClaimsJws(token);
            return true;
        } catch (SecurityException | MalformedJwtException e) {
            logger.error("Invalid JWT signature, 유효하지 않는 JWT 서명 입니다.");
        } catch (ExpiredJwtException e) {
            logger.error("Expired JWT token, 만료된 JWT token 입니다.");
            return false;
        } catch (UnsupportedJwtException e) {
            logger.error("Unsupported JWT token, 지원되지 않는 JWT 토큰 입니다.");
        } catch (IllegalArgumentException e) {
            logger.error("JWT claims is empty, 잘못된 JWT 토큰 입니다.");
        }
        return false;
    }


    public boolean validateAllToken(HttpServletRequest request, HttpServletResponse response) {
        // HttpServlet에서 쿠키 가져와 JWT 토큰 꺼내
        String accessToken = getTokenFromRequest(ACCESS_HEADER, request);
        String refreshToken = getTokenFromRequest(REFRESH_HEADER, request);

        // 쿠키에서 JWT 토큰 자르기
        accessToken = substringToken(accessToken);
        refreshToken = substringToken(refreshToken);

        if(accessToken != null){ // accessToken 비어있지 않고
            if(validateToken(accessToken) && redisService.getValue(accessToken) == null){ // 검증이 완료되고 DB에 refresh token이 만료되지 않
                return true;
            }else if(!validateToken(accessToken) && refreshToken != null){ //검증은 안되는데 refresh 토큰이 값이 있어
                boolean validateRefreshToken = validateToken(refreshToken); // refresh token 검증
                boolean isRefreshToken = existsRefreshToken(refreshToken); // refresh token DB 존재
                if(validateRefreshToken && isRefreshToken){
                    String email = getUserInfoFromToken(refreshToken).getSubject();
                    UserRoleEnum role = getRoles(email);
                    String newAccessToken = createAccessToken(email, role);
                    addJwtToCookie(newAccessToken, ACCESS_HEADER, response);
                    return true;
                }
                return false;
            }
        }
        return false;
    }

    private boolean existsRefreshToken(String refreshToken) {
        return redisService.getValue(refreshToken) != null;
    }

    public UserRoleEnum getRoles(String email){
        return userRepository.findByEmail(email).get().getRole();
    }

    // token에서 사용자 정보 가져오기
    public Claims getUserInfoFromToken(String token){
        return Jwts.parserBuilder().setSigningKey(key).build().parseClaimsJws(token).getBody();
    }

    public Long getExpiration(String accessToken) {
        Date expiration = Jwts.parserBuilder()
                .setSigningKey(key)
                .build()
                .parseClaimsJws(accessToken)
                .getBody()
                .getExpiration();
        Long now = new Date().getTime();
        return expiration.getTime() - now;
    }
}
