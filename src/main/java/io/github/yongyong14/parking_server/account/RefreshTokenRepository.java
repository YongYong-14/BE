package io.github.yongyong14.parking_server.account;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {
    @Query("select t.userId from RefreshToken t where t.tokenHash = :hash")
    Optional<UUID> findOwner(@Param("hash") String hash);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from RefreshToken t where t.tokenHash = :hash and t.userId = :userId")
    Optional<RefreshToken> lockByHash(@Param("hash") String hash, @Param("userId") UUID userId);

    @Modifying
    @Query("""
        update RefreshToken t set t.revokedAt = :now
        where t.userId = :userId and t.familyId = :familyId and t.revokedAt is null
        """)
    int revokeFamily(@Param("userId") UUID userId, @Param("familyId") UUID familyId, @Param("now") Instant now);
}
