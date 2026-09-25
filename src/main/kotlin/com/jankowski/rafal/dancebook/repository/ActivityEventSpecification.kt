package com.jankowski.rafal.dancebook.repository

import com.jankowski.rafal.dancebook.model.ActivityEvent
import com.jankowski.rafal.dancebook.model.AppUser
import com.jankowski.rafal.dancebook.model.CustomList
import com.jankowski.rafal.dancebook.model.Material
import com.jankowski.rafal.dancebook.model.NotificationReadStatus
import com.jankowski.rafal.dancebook.model.Role
import com.jankowski.rafal.dancebook.model.TargetType
import org.springframework.data.jpa.domain.Specification
import java.util.UUID

object ActivityEventSpecification {

    fun visibleTo(user: AppUser?): Specification<ActivityEvent> {
        return Specification { root, query, cb ->
            if (user?.role == Role.ADMIN) {
                return@Specification cb.conjunction()
            }
            if (query == null) {
                return@Specification cb.conjunction()
            }

            val isActor = if (user != null) cb.equal(root.get<AppUser>("actor"), user) else cb.disjunction()

            // Subqueries for live Material
            val liveMaterialSubquery = query.subquery(Long::class.java)
            val matRoot = liveMaterialSubquery.from(Material::class.java)
            liveMaterialSubquery.select(cb.literal(1L))
            val matVis = MaterialSpecification.visibilityPredicate(matRoot, query, cb, user)
            liveMaterialSubquery.where(
                cb.equal(matRoot.get<UUID>("id"), root.get<UUID>("targetId")),
                matVis
            )
            val isLiveVisibleMaterial = cb.exists(liveMaterialSubquery)

            val matExistsSubquery = query.subquery(Long::class.java)
            val matExistsRoot = matExistsSubquery.from(Material::class.java)
            matExistsSubquery.select(cb.literal(1L))
            matExistsSubquery.where(cb.equal(matExistsRoot.get<UUID>("id"), root.get<UUID>("targetId")))
            val isDeletedPublicMaterial = cb.and(
                cb.not(cb.exists(matExistsSubquery)),
                cb.equal(root.get<String>("targetVisibility"), "PUBLIC")
            )

            val materialPredicate = cb.and(
                cb.equal(root.get<TargetType>("targetType"), TargetType.MATERIAL),
                cb.or(isLiveVisibleMaterial, isDeletedPublicMaterial)
            )

            // Subqueries for live CustomList
            val liveListSubquery = query.subquery(Long::class.java)
            val listRoot = liveListSubquery.from(CustomList::class.java)
            liveListSubquery.select(cb.literal(1L))
            val listVis = CustomListSpecification.visibilityPredicate(listRoot, query, cb, user)
            liveListSubquery.where(
                cb.equal(listRoot.get<UUID>("id"), root.get<UUID>("targetId")),
                listVis
            )
            val isLiveVisibleList = cb.exists(liveListSubquery)

            val listExistsSubquery = query.subquery(Long::class.java)
            val listExistsRoot = listExistsSubquery.from(CustomList::class.java)
            listExistsSubquery.select(cb.literal(1L))
            listExistsSubquery.where(cb.equal(listExistsRoot.get<UUID>("id"), root.get<UUID>("targetId")))
            val isDeletedPublicList = cb.and(
                cb.not(cb.exists(listExistsSubquery)),
                cb.equal(root.get<String>("targetVisibility"), "PUBLIC")
            )

            val listPredicate = cb.and(
                cb.equal(root.get<TargetType>("targetType"), TargetType.CUSTOM_LIST),
                cb.or(isLiveVisibleList, isDeletedPublicList)
            )

            val otherTypesPredicate = cb.or(
                cb.equal(root.get<TargetType>("targetType"), TargetType.DANCE_FIGURE),
                cb.equal(root.get<TargetType>("targetType"), TargetType.TRAINING_EVENT)
            )

            cb.or(isActor, materialPredicate, listPredicate, otherTypesPredicate)
        }
    }

    fun isUnreadFor(user: AppUser): Specification<ActivityEvent> {
        return Specification { root, query, cb ->
            if (query == null) {
                return@Specification cb.conjunction()
            }
            val readSubquery = query.subquery(Long::class.java)
            val statusRoot = readSubquery.from(NotificationReadStatus::class.java)
            readSubquery.select(cb.literal(1L))
            readSubquery.where(
                cb.equal(statusRoot.get<ActivityEvent>("event"), root),
                cb.equal(statusRoot.get<AppUser>("user"), user),
                cb.equal(statusRoot.get<Boolean>("isRead"), true)
            )
            cb.not(cb.exists(readSubquery))
        }
    }
}
