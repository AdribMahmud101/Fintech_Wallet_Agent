package app.fusion.wallet.data

import java.math.BigInteger

object BucketMath {
    fun compute(cash: BigInteger, owed: BigInteger, buffer: BigInteger, deployed: BigInteger): Buckets {
        require(listOf(cash, owed, buffer, deployed).all { it.signum() >= 0 })
        val free = (cash - owed).max(BigInteger.ZERO)
        return Buckets(
            reserve = Money(cash.min(owed)), owed = Money(owed), buffer = Money(free.min(buffer)),
            surplus = Money((free - buffer).max(BigInteger.ZERO)), free = Money(free),
            shortfall = Money((owed - cash).max(BigInteger.ZERO)), deployed = Money(deployed),
            total = Money(cash + deployed), bufferTarget = Money(buffer),
        )
    }
}
