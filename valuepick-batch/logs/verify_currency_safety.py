"""03_build_indicators.py의 currency F.first() 안전성을, 실제 코드가 쓰는 stacked/keys 기준으로 검증.
어제 에이전트 검증이 raw/financials(원본) 기준이었어서 stacked(언피벗 후)와 다를 수 있다는 지적이 있었다."""
import importlib.util, sys
from pyspark.sql import SparkSession, functions as F

spec = importlib.util.spec_from_file_location("job03", "/opt/spark-apps/jobs/03_build_indicators.py")
job03 = importlib.util.module_from_spec(spec)
sys.modules["job03"] = job03
spec.loader.exec_module(job03)

spark = SparkSession.builder.appName("verify-currency-safety").getOrCreate()
spark.sparkContext.setLogLevel("ERROR")

fin = spark.read.parquet("/opt/spark-apps/data/raw/financials")
stacked = job03.stack_financial_years(fin)

keys = ["stock_code", "fs_div", "year"]
distinct_currency_per_group = stacked.filter(F.col("currency").isNotNull()) \
    .groupBy(*keys).agg(F.countDistinct("currency").alias("n_currencies"))

mixed = distinct_currency_per_group.filter(F.col("n_currencies") > 1)
mixed_count = mixed.count()
print(f"stacked 기준, (stock_code,fs_div,year) 그룹 중 currency가 2종 이상 섞인 경우: {mixed_count}건")
if mixed_count > 0:
    mixed.show(20, truncate=False)

spark.stop()
