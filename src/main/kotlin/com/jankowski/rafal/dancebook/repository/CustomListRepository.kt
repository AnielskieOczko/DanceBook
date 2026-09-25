package com.jankowski.rafal.dancebook.repository

import com.jankowski.rafal.dancebook.model.AppUser
import com.jankowski.rafal.dancebook.model.CustomList
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.JpaSpecificationExecutor
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface CustomListRepository : JpaRepository<CustomList, UUID>, JpaSpecificationExecutor<CustomList> {

    fun findAllByOwner(owner: AppUser): List<CustomList>
}
