package com.jankowski.rafal.dancebook.repository

import com.jankowski.rafal.dancebook.model.TrainingSeries
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface TrainingSeriesRepository : JpaRepository<TrainingSeries, UUID>
