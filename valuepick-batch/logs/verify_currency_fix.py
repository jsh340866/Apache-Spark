import importlib.util, sys
from pyspark.sql import SparkSession, functions as F

spec = importlib.util.spec_from_file_location("job03", "/opt/spark-apps/jobs/03_build_indicators.py")
job03 = importlib.util.module_from_spec(spec)
sys.modules["job03"] = job03
spec.loader.exec_module(job03)

spark = SparkSession.builder.appName("verify-currency-fix").getOrCreate()
spark.sparkContext.setLogLevel("ERROR")

fin = spark.read.parquet("/opt/spark-apps/data/raw/financials")
stacked = job03.stack_financial_years(fin)
keys = ["stock_code", "fs_div", "year"]

# 수정 후 기준: (keys, rcept_no) 안에서 currency가 여전히 여러 개인지
per_rcept = stacked.filter(F.col("currency").isNotNull()).groupBy(*keys, "rcept_no") \
    .agg(F.countDistinct("currency").alias("n"))
mixed = per_rcept.filter(F.col("n") > 1)
print(f"(keys+rcept_no) 그룹 내 currency 2종 이상: {mixed.count()}건")
mixed.show(20, truncate=False)

# 전체 pivot_financials 실행해서 950190/241560이 정상 처리되는지, 전체 row 수 변화 없는지
before_count = stacked.select(*keys).distinct().count()
result = job03.pivot_financials(stacked)
after_count = result.count()
print(f"distinct keys 수: {before_count}  pivot_financials 결과 행 수: {after_count} (같아야 정상 - fan-out 없음)")

print("=== 950190 결과 확인 ===")
result.filter(F.col("stock_code") == "950190").select("stock_code", "fs_div", "year", "rcept_no", "currency").orderBy("year").show()

spark.stop()
