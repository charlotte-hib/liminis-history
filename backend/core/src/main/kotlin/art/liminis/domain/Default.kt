package art.liminis.domain

/**
 * The constructor MapStruct builds with. The JPA plugin gives every entity a no-arg constructor for Hibernate,
 * which MapStruct would otherwise prefer. MapStruct recognises any annotation named `Default` and ships none.
 */
@Target(AnnotationTarget.CONSTRUCTOR)
@Retention(AnnotationRetention.BINARY)
annotation class Default
