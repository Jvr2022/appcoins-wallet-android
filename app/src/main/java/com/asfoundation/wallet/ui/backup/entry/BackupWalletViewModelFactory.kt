package com.asfoundation.wallet.ui.backup.entry

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.asfoundation.wallet.base.RxSchedulers
import com.asfoundation.wallet.util.CurrencyFormatUtils
import com.asfoundation.wallet.wallets.usecases.GetWalletInfoUseCase

class BackupWalletViewModelFactory(
  private val data: BackupWalletData,
  private val getWalletInfoUseCase: GetWalletInfoUseCase,
  private val currencyFormatUtils: CurrencyFormatUtils,
  private val rxSchedulers: RxSchedulers,
) :
  ViewModelProvider.Factory {
  override fun <T : ViewModel?> create(modelClass: Class<T>): T {
    return BackupWalletViewModel(data, getWalletInfoUseCase, currencyFormatUtils, rxSchedulers) as T
  }
}
