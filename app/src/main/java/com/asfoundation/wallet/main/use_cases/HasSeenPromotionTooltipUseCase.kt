package com.asfoundation.wallet.main.use_cases

import io.reactivex.Single
import repository.CommonsPreferencesDataSource
import javax.inject.Inject

class HasSeenPromotionTooltipUseCase @Inject constructor(
  val commonsPreferencesDataSource: CommonsPreferencesDataSource
) {

  operator fun invoke(): Single<Boolean> =
    Single.just(commonsPreferencesDataSource.hasSeenPromotionTooltip())
}