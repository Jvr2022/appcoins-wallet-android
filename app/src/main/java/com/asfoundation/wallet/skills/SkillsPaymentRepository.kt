package com.asfoundation.wallet.skills

import android.content.Context
import android.content.Intent
import cm.aptoide.skills.interfaces.ExternalSkillsPaymentProvider
import cm.aptoide.skills.model.CreatedTicket
import cm.aptoide.skills.model.Price
import cm.aptoide.skills.util.EskillsPaymentData
import com.asfoundation.wallet.base.RxSchedulers
import com.asfoundation.wallet.repository.CurrencyConversionService
import com.asfoundation.wallet.topup.TopUpActivity
import com.asfoundation.wallet.util.CurrencyFormatUtils
import com.asfoundation.wallet.util.WalletCurrency
import com.asfoundation.wallet.wallets.usecases.GetWalletInfoUseCase
import io.reactivex.Completable
import io.reactivex.Single
import java.math.BigDecimal

class SkillsPaymentRepository(
    private val currencyConversionService: CurrencyConversionService,
    private val currencyFormatUtils: CurrencyFormatUtils,
    private val appCoinsCreditsPayment: AppCoinsCreditsPayment,
    private val schedulers: RxSchedulers,
    private val getWalletInfoUseCase: GetWalletInfoUseCase
) : ExternalSkillsPaymentProvider {
  override fun getBalance(): Single<BigDecimal> {
    return getWalletInfoUseCase(null, cached = false, updateFiat = false)
        .subscribeOn(schedulers.io)
        .map { it.walletBalance.creditsBalance.token.amount }
  }

  override fun getLocalFiatAmount(value: BigDecimal, currency: String): Single<Price> {
    return currencyConversionService.getLocalFiatAmount(value.toString(), currency)
        .map { Price(it.amount, it.currency, it.symbol) }
        .subscribeOn(schedulers.io)
  }

  override fun getFiatToAppcAmount(value: BigDecimal, currency: String): Single<Price> {
    return currencyConversionService.getFiatToAppcAmount(value.toString(), currency)
        .map { Price(it.amount, it.currency, it.symbol) }
        .subscribeOn(schedulers.io)
  }

  override fun getFormattedAppcAmount(value: BigDecimal, currency: String): Single<String> {
    return getFiatToAppcAmount(value, currency)
        .map { currencyFormatUtils.formatCurrency(it.amount, WalletCurrency.APPCOINS) }
  }

  override fun sendUserToTopUpFlow(context: Context) {
    val intent = TopUpActivity.newIntent(context)
        .apply { flags = Intent.FLAG_ACTIVITY_SINGLE_TOP }
    context.startActivity(intent)
  }

  override fun pay(eskillsPaymentData: EskillsPaymentData, ticket: CreatedTicket): Completable {
    return appCoinsCreditsPayment.pay(eskillsPaymentData, ticket)
        .subscribeOn(schedulers.io)
  }
}
