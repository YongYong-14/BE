package io.github.yongyong14.parking_server.account;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

interface UserRepository extends JpaRepository<User, UUID> {
    @Modifying
    @Query(value = """
        INSERT INTO public.users (id, kakao_id) VALUES (:id, :kakaoId)
        ON CONFLICT (kakao_id) DO NOTHING
        """, nativeQuery = true)
    int insertIfAbsent(@Param("id") UUID id, @Param("kakaoId") long kakaoId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from User u where u.kakaoId = :kakaoId")
    Optional<User> lockByKakaoId(@Param("kakaoId") long kakaoId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from User u where u.id = :id")
    Optional<User> lockById(@Param("id") UUID id);

    @Query("select u.kakaoId from User u where u.id = :id")
    Optional<Long> findKakaoId(@Param("id") UUID id);

    @Query(value = """
        SELECT EXISTS (SELECT 1 FROM public.vehicles WHERE user_id = :id AND deleted_at IS NULL)
        """, nativeQuery = true)
    boolean hasActiveVehicle(@Param("id") UUID id);
}
