package io.github.yongyong14.parking_server.account;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

interface UserAgreementRepository extends JpaRepository<UserAgreement, Long> {
    @Query("""
        select a from UserAgreement a
        where a.userId = :userId and a.id = (
            select max(b.id) from UserAgreement b where b.userId = :userId and b.agreementType = a.agreementType
        )
        order by a.id
        """)
    List<UserAgreement> findLatestByType(@Param("userId") UUID userId);
}
