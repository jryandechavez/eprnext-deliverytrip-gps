from setuptools import find_packages, setup

setup(
    name="gps_tracker",
    version="1.0.0",
    description="Bluecore GPS receiver and location log for ERPNext",
    author="BlueCore Solutions Corp.",
    packages=find_packages(),
    include_package_data=True,
    zip_safe=False,
)
